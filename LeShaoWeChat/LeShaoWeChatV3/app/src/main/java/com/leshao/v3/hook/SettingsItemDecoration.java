package com.leshao.v3.hook;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class SettingsItemDecoration {

    private View cardView;
    private int cardHeight;
    private boolean measured;

    private View buildCard(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(40, 28, 40, 28);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setBackgroundColor(0xFFFFFFFF);

        ImageView icon = new ImageView(ctx);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(140, 140);
        iconLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        icon.setLayoutParams(iconLp);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageResource(ctx.getResources().getIdentifier(
                "ic_launcher", "drawable", "com.leshao.v3"));
        root.addView(icon);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(24, 0, 0, 0);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textCol.setLayoutParams(colLp);

        TextView title = new TextView(ctx);
        title.setTextSize(16);
        title.setTextColor(0xFF333333);
        title.setText("乐少助手 V3");
        textCol.addView(title);

        TextView subtitle = new TextView(ctx);
        subtitle.setTextSize(12);
        subtitle.setTextColor(0xFF999999);
        subtitle.setText("红包助手 | 好友请求 | 消息增强");
        textCol.addView(subtitle);

        root.addView(textCol);

        ImageView arrow = new ImageView(ctx);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(72, 72);
        arrowLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        arrow.setLayoutParams(arrowLp);
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int arrowId = ctx.getResources().getIdentifier(
                "mm_title_btn_right", "drawable", "com.tencent.mm");
        if (arrowId != 0) {
            arrow.setImageResource(arrowId);
        }
        root.addView(arrow);

        return root;
    }

    public void ensureCardMeasured(View parent) {
        if (measured && cardView != null) return;
        measured = true;

        if (cardView == null) {
            cardView = buildCard(parent.getContext());
        }
        int w = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        if (w <= 0) w = parent.getWidth();
        int wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        cardView.measure(wSpec, hSpec);
        cardHeight = cardView.getMeasuredHeight();
        if (cardHeight <= 0) cardHeight = 160;
        cardView.layout(0, 0, w, cardHeight);
    }

    public int getCardHeight() {
        return measured ? cardHeight : 0;
    }

    public void drawCard(Canvas c, View parent) {
        if (cardView == null || cardHeight <= 0) return;
        c.save();
        c.translate(parent.getPaddingLeft(), parent.getPaddingTop());
        cardView.draw(c);
        c.restore();
    }

    public boolean handleTouch(View parent, MotionEvent e) {
        if (cardView == null || e.getAction() != MotionEvent.ACTION_UP) return false;
        if (cardHeight <= 0) return false;
        if (e.getY() <= cardHeight + parent.getPaddingTop()) {
            try {
                Context ctx = parent.getContext();
                Class<?> cls = Class.forName("com.leshao.v3.SettingsActivity", false,
                        ctx.getClassLoader());
                android.content.Intent i = new android.content.Intent(ctx, cls);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {}
            return true;
        }
        return false;
    }
}
