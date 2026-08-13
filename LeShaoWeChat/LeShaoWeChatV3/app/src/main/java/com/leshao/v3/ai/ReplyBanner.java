package com.leshao.v3.ai;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import com.leshao.v3.LogWriter;

public class ReplyBanner {
    private static final String TAG = "ReplyBanner";
    public interface OnPick { void onPick(String text); }

    private static WindowManager sWM;
    private static View sBanner;
    private static LinearLayout sChips;
    private static Activity sAct;
    private static OnPick sPick;

    public static boolean isShowing() { return sBanner != null; }

    public static void show(Activity act, List<String> replies, OnPick pick) {
        if (act == null || replies == null || replies.isEmpty()) return;
        hide();
        sAct = act;
        sPick = pick;
        sWM = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);

        LinearLayout banner = new LinearLayout(act);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(16, 12, 16, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F5336D7B"));
        banner.setBackground(bg);

        TextView title = new TextView(act);
        title.setText("推荐回复（点击使用）");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        banner.addView(title);

        HorizontalScrollView hsv = new HorizontalScrollView(act);
        hsv.setHorizontalScrollBarEnabled(false);
        sChips = new LinearLayout(act);
        sChips.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(sChips);
        banner.addView(hsv);

        float d = act.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = (int) (52 * d);
        sBanner = banner;
        try {
            sWM.addView(banner, lp);
        } catch (Throwable t) {
            sBanner = null;
            sChips = null;
            return;
        }

        renderChips(replies);
        banner.postDelayed(ReplyBanner::hide, 15_000);
    }

    public static void update(List<String> replies) {
        if (sBanner == null || sChips == null || replies == null || replies.isEmpty()) return;
        renderChips(replies);
        sBanner.postDelayed(ReplyBanner::hide, 15_000);
    }

    private static void renderChips(List<String> replies) {
        sChips.removeAllViews();
        Activity act = sAct;
        for (String r : replies) {
            TextView chip = new TextView(act);
            chip.setText(r);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(14);
            chip.setPadding(24, 14, 24, 14);
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(Color.parseColor("#FFFFFF"));
            cb.setAlpha(40);
            cb.setCornerRadius(60);
            chip.setBackground(cb);
            chip.setClickable(true);
            chip.setOnClickListener(v -> {
                LogWriter.log(TAG, "点击推荐回复: " + r);
                hide();
                if (sPick != null) sPick.onPick(r);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 16;
            sChips.addView(chip, lp);
        }
    }

    public static void hide() {
        if (sWM != null && sBanner != null) {
            try { sWM.removeView(sBanner); } catch (Throwable ignored) {}
        }
        sBanner = null;
        sChips = null;
        sWM = null;
        sAct = null;
        sPick = null;
    }
}
