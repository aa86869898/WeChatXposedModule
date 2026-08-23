package com.leshao.v3.ai;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.LogWriter;
import com.leshao.v3.UnifiedPrefs;

public class FloatingBall {
    private static final String TAG = "FloatingBall";
    private static final String SP = "leshao_ball_pos";
    private static WindowManager sWM;
    private static Activity sAct;
    private static View sBall;
    private static LinearLayout sMenu;

    public static void show(Activity act, ClassLoader cl) {
        hide();
        if (act == null) return;
        sAct = act;
        sWM = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
        float d = act.getResources().getDisplayMetrics().density;
        int sz = (int) (44 * d);

        int[] pos = restorePos(act, d, sz);

        sBall = makeBall(act);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                sz, sz,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = pos[0];
        lp.y = pos[1];
        try {
            sWM.addView(sBall, lp);
        } catch (Throwable t) {
            LogWriter.log(TAG, "show: addView 失败: " + t.getMessage());
            sBall = null;
            return;
        }
        LogWriter.log(TAG, "show: 注入悬浮球 x=" + pos[0] + " y=" + pos[1]);
        sBall.setOnTouchListener(makeTouch());
    }

    private static int[] restorePos(Activity act, float d, int sz) {
        int w = act.getResources().getDisplayMetrics().widthPixels;
        int h = act.getResources().getDisplayMetrics().heightPixels;
        int defX = w - sz - (int) (14 * d);
        int defY = h - sz - (int) (150 * d);
        SharedPreferences sp = UnifiedPrefs.get(act, SP);
        int x = sp.getInt("x", defX);
        int y = sp.getInt("y", defY);
        if (x < -w || x > w) x = defX;
        if (y < -h || y > h) y = defY;
        return new int[]{x, y};
    }

    private static void savePos() {
        if (sAct == null || sBall == null) return;
        try {
            WindowManager.LayoutParams wp = (WindowManager.LayoutParams) sBall.getLayoutParams();
            UnifiedPrefs.get(sAct, SP).edit()
                    .putInt("x", wp.x).putInt("y", wp.y).apply();        } catch (Throwable ignored) {}
    }

    private static View.OnTouchListener makeTouch() {
        return new View.OnTouchListener() {
            float dx, dy, downX, downY;
            long downTime;
            @Override public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams wp = (WindowManager.LayoutParams) v.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = wp.x - e.getRawX();
                        dy = wp.y - e.getRawY();
                        downX = e.getRawX();
                        downY = e.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        wp.x = (int) (e.getRawX() + dx);
                        wp.y = (int) (e.getRawY() + dy);
                        try { sWM.updateViewLayout(v, wp); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis() - downTime < 300
                                && Math.abs(e.getRawX() - downX) < 15
                                && Math.abs(e.getRawY() - downY) < 15) {
                            LogWriter.log(TAG, "tap 检测，打开/关闭菜单");
                            toggleMenu();
                        } else {
                            savePos();
                        }
                        return true;
                }
                return false;
            }
        };
    }

    private static void toggleMenu() {
        if (sAct == null || sWM == null || sBall == null) return;
        if (sMenu != null) {
            try { sWM.removeView(sMenu); } catch (Throwable ignored) {}
            sMenu = null;
            return;
        }
        sMenu = new LinearLayout(sAct);
        sMenu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F0222222"));
        bg.setCornerRadius(20);
        sMenu.setBackground(bg);
        sMenu.setPadding(8, 8, 8, 8);

        addItem(sAct, sMenu, "📊 聊天总结", () -> {
            try {
                SummaryFeature.doSummary(sAct, sAct.getClassLoader());
            } catch (Throwable t) { android.util.Log.e("WxAi", "总结", t); }
        });
        addItem(sAct, sMenu, "✍️ 润色", () -> AiFeature.process(sAct, AiFeature.Action.POLISH, "自然"));
        addItem(sAct, sMenu, "🎭 情绪分析", () -> AiFeature.process(sAct, AiFeature.Action.EMOTION, null));
        addItem(sAct, sMenu, "🔑 关键词/待办", () -> AiFeature.process(sAct, AiFeature.Action.KEYWORD, null));
        addItem(sAct, sMenu, "⚙️ 控制面板", () -> {
            try {
                AiPanelDialog.show(sAct);
            } catch (Throwable t) { android.util.Log.e("WxAi", "打开控制面板失败", t); }
        });

        float d = sAct.getResources().getDisplayMetrics().density;
        int screenW = sAct.getResources().getDisplayMetrics().widthPixels;
        int screenH = sAct.getResources().getDisplayMetrics().heightPixels;
        sMenu.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int mw = sMenu.getMeasuredWidth();
        int mh = sMenu.getMeasuredHeight();
        if (mw <= 0) mw = (int) (240 * d);
        if (mh <= 0) mh = (int) (320 * d);
        WindowManager.LayoutParams ballLp = (WindowManager.LayoutParams) sBall.getLayoutParams();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        int menuX = ballLp.x + (int) (44 * d) - mw;
        if (menuX < (int) (8 * d)) menuX = (int) (8 * d);
        lp.x = menuX;
        int menuY = ballLp.y + (int) (44 * d) + (int) (8 * d);
        if (menuY + mh > screenH) menuY = ballLp.y - mh;
        if (menuY < (int) (48 * d)) menuY = (int) (48 * d);
        lp.y = menuY;
        try {
            sWM.addView(sMenu, lp);
            LogWriter.log(TAG, "toggleMenu: 菜单已添加 x=" + lp.x + " y=" + lp.y
                    + " 宽=" + mw + " 高=" + mh + " 屏幕=" + screenW + "x" + screenH);
        } catch (Throwable t) {
            LogWriter.log(TAG, "toggleMenu: addView 失败: " + t.getMessage());
        }
    }

    private static void addItem(Activity act, LinearLayout menu, String label, Runnable r) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        tv.setPadding(40, 24, 40, 24);
        tv.setOnClickListener(v -> {
            LogWriter.log(TAG, "菜单项点击: " + label);
            if (sMenu != null && sWM != null) {
                try { sWM.removeView(sMenu); } catch (Throwable ignored) {}
                sMenu = null;
            }
            r.run();
        });
        menu.addView(tv);
    }

    public static void hide() {
        if (sWM != null) {
            if (sBall != null) { try { sWM.removeView(sBall); } catch (Throwable ignored) {} }
            if (sMenu != null) { try { sWM.removeView(sMenu); } catch (Throwable ignored) {} }
        }
        sBall = null;
        sMenu = null;
        sWM = null;
        sAct = null;
    }

    private static View makeBall(Activity act) {
        TextView b = new TextView(act);
        b.setText("AI");
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF576B95"));
        bg.setShape(GradientDrawable.OVAL);
        b.setBackground(bg);
        b.setAlpha(0.9f);
        return b;
    }
}
