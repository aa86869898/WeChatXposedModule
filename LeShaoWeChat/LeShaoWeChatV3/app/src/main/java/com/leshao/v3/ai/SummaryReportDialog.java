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

import java.util.List;

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
        root.addView(card(act, "📝 字数 / 最长", stats.meChars + "·" + stats.otherChars + " / " + stats.maxLen + " 字"));
        root.addView(card(act, "📅 时间跨度", stats.spanText()));
        root.addView(card(act, "⏱ 回复速度", stats.replySpeedText()));
        root.addView(card(act, "🔥 最活跃", stats.peakHours()));

        root.addView(sectionTitle(act, "🧩 消息类型占比"));
        root.addView(new TypeBarView(act, stats));

        if (!stats.topKeywords.isEmpty()) {
            root.addView(sectionTitle(act, "🏷 高频话题词"));
            root.addView(keywordCloud(act, stats.topKeywords));
        }

        root.addView(sectionTitle(act, "⏰ 24小时活跃分布"));
        root.addView(new BarChartView(act, stats.hourDist));

        root.addView(sectionTitle(act, "🎯 发言占比"));
        root.addView(new PieChartView(act, stats.meCount, stats.otherCount));

        root.addView(sectionTitle(act, "📝 AI 深度分析报告"));

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

    private static TextView sectionTitle(Activity act, String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(Color.parseColor("#FF9CA3AF"));
        t.setTextSize(14);
        t.setPadding(0, 24, 0, 8);
        return t;
    }

    private static View keywordCloud(Activity act, List<String> words) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(row);
        for (String w : words) {
            TextView chip = new TextView(act);
            chip.setText(w);
            chip.setTextColor(Color.parseColor("#FF93C5FD"));
            chip.setTextSize(13);
            chip.setPadding(18, 10, 18, 10);
            chip.setBackgroundColor(Color.parseColor("#FF1E3A5F"));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = 12;
            cp.bottomMargin = 12;
            row.addView(chip, cp);
        }
        return box;
    }

    static class TypeBarView extends View {
        final SummaryFeature.SummaryStats s;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        public TypeBarView(Activity act, SummaryFeature.SummaryStats s) { super(act); this.s = s; }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int[] vals = {s.textCount, s.imageCount, s.voiceCount, s.emojiCount, s.videoCount, s.appCount, s.otherTypeCount};
            String[] names = {"文本", "图片", "语音", "表情", "视频", "链接", "其他"};
            int[] colors = {0xFF60A5FA, 0xFF34D399, 0xFFFBBF24, 0xFFF87171, 0xFFA78BFA, 0xFF22D3EE, 0xFF6B7280};
            int total = Math.max(1, s.total);
            int w = getWidth();
            float x = 0;
            for (int i = 0; i < vals.length; i++) {
                if (vals[i] == 0) continue;
                float seg = vals[i] * (float) w / total;
                paint.setColor(colors[i]);
                c.drawRect(x, 0, x + seg, getHeight(), paint);
                x += seg;
            }
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(View.MeasureSpec.getSize(w), 48);
        }
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
