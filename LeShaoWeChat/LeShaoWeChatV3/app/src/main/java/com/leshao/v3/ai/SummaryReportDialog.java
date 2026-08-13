package com.leshao.v3.ai;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class SummaryReportDialog {

    public static void show(Activity act, String summaryText, SummaryFeature.SummaryStats stats) {
        Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 80, 40, 40);
        root.setBackgroundColor(Color.parseColor("#FF111827"));

        TextView title = new TextView(act);
        title.setText("📊 聊天总结报告");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        root.addView(card(act, "💬 总消息数", String.valueOf(stats.total)));
        root.addView(card(act, "👤 我 / 对方", stats.meCount + " / " + stats.otherCount));

        TextView hTitle = new TextView(act);
        hTitle.setText("⏰ 24小时活跃分布");
        hTitle.setTextColor(Color.parseColor("#FF9CA3AF"));
        hTitle.setTextSize(14);
        hTitle.setPadding(0, 24, 0, 8);
        root.addView(hTitle);
        root.addView(new BarChartView(act, stats.hourDist));

        TextView pTitle = new TextView(act);
        pTitle.setText("🎯 发言占比");
        pTitle.setTextColor(Color.parseColor("#FF9CA3AF"));
        pTitle.setTextSize(14);
        pTitle.setPadding(0, 24, 0, 8);
        root.addView(pTitle);
        root.addView(new PieChartView(act, stats.meCount, stats.otherCount));

        TextView sTitle = new TextView(act);
        sTitle.setText("📝 AI 总结");
        sTitle.setTextColor(Color.parseColor("#FF9CA3AF"));
        sTitle.setTextSize(14);
        sTitle.setPadding(0, 24, 0, 8);
        root.addView(sTitle);

        TextView body = new TextView(act);
        body.setText(summaryText);
        body.setTextColor(Color.WHITE);
        body.setTextSize(15);
        body.setLineSpacing(6, 1.1f);
        root.addView(body);

        TextView close = new TextView(act);
        close.setText("关闭");
        close.setTextColor(Color.WHITE);
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, 40, 0, 20);
        close.setOnClickListener(v -> d.dismiss());
        root.addView(close);

        scroll.addView(root);
        d.setContentView(scroll);
        d.show();
    }

    private static View card(Activity act, String label, String value) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setPadding(24, 20, 24, 20);
        c.setBackgroundColor(Color.parseColor("#FF1F2937"));
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Color.parseColor("#FF9CA3AF"));
        l.setTextSize(15);
        TextView v = new TextView(act);
        v.setText(value);
        v.setTextColor(Color.parseColor("#FF60A5FA"));
        v.setTextSize(18);
        v.setGravity(Gravity.END);
        c.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        c.setLayoutParams(lp);
        return c;
    }

    static class BarChartView extends View {
        final int[] data;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        public BarChartView(Activity a, int[] d) { super(a); this.data = d; }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            int max = 1;
            for (int x : data) max = Math.max(max, x);
            float bw = w / 24f;
            for (int i = 0; i < 24; i++) {
                float bh = (data[i] / (float) max) * (h - 20);
                paint.setColor(Color.parseColor("#FF3B82F6"));
                c.drawRect(i * bw + 2, h - bh, (i + 1) * bw - 2, h, paint);
            }
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(View.MeasureSpec.getSize(w), 320);
        }
    }

    static class PieChartView extends View {
        final int a, b;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        public PieChartView(Activity act, int a, int b) { super(act); this.a = a; this.b = b; }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int total = Math.max(1, a + b);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - 20;
            float sweepA = a * 360f / total;
            paint.setColor(Color.parseColor("#FF60A5FA"));
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90, sweepA, true, paint);
            paint.setColor(Color.parseColor("#FF34D399"));
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90 + sweepA, 360 - sweepA, true, paint);
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(View.MeasureSpec.getSize(w), 320);
        }
    }
}
