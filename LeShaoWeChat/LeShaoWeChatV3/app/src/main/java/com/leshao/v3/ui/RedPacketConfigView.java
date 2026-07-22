package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.RedPacketHook;

public class RedPacketConfigView {

    private static final int CLR_CARD    = 0xB8FFFFFF;
    private static final int CLR_WHITE   = 0xFFFFFFFF;
    private static final int CLR_TEXT    = 0xFF281838;
    private static final int CLR_TEXT2   = 0xFF786890;
    private static final int CLR_BG      = 0xFFF4F0FF;
    private static final int CLR_DIV     = 0xFFE8DCF0;
    private static final int CLR_ACCENT  = 0xFFFF4298;

    private static final String KEY_REDPACKET_ENABLED = "ls_redpacket_enabled";
    private static final String KEY_REDPACKET_DELAY   = "ls_redpacket_delay";

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CLR_BG);
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        SharedPreferences prefs = ContextManager.getPrefs();
        boolean enabled = prefs != null && prefs.getBoolean(KEY_REDPACKET_ENABLED, false);
        int delayMs = prefs != null ? prefs.getInt(KEY_REDPACKET_DELAY, 500) : 500;

        root.addView(sectionLabel(ctx, "核心功能"));
        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(switchRow(ctx, d, "自动秒抢红包", "检测到红包后自动点击\"开\"按钮领取", enabled, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_REDPACKET_ENABLED, on).apply();
            RedPacketHook.setEnabled(on);
        }));
        root.addView(card1);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, "延迟设置"));

        LinearLayout card2 = makeCard(ctx, d);
        card2.addView(delayRow(ctx, d, delayMs, ms -> {
            if (prefs != null) prefs.edit().putInt(KEY_REDPACKET_DELAY, ms).apply();
        }));
        root.addView(card2);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, "功能说明"));

        LinearLayout card3 = makeCard(ctx, d);
        card3.addView(infoRow(ctx, d, "抢红包原理",
            "Hook LuckyMoneyNewReceiveUI.initView()\n自动查找并点击\"开\"按钮,\n支持普通红包、企业红包、香港红包"));
        card3.addView(itemDivider(ctx, d));
        card3.addView(infoRow(ctx, d, "聊天列表自动抢",
            "Hook ChattingUI.onResume()\n扫描聊天界面红包气泡自动点击"));
        card3.addView(itemDivider(ctx, d));
        card3.addView(infoRow(ctx, d, "当前延迟",
            delayMs + "ms (滑动下方滑块调整 100-2600ms)"));
        root.addView(card3);

        return root;
    }

    private static View delayRow(Context ctx, float d, int currentMs, DelayCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(CLR_WHITE);

        LinearLayout labelRow = new LinearLayout(ctx);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView label = new TextView(ctx);
        label.setText("延迟时间: ");
        label.setTextSize(13);
        label.setTextColor(CLR_TEXT);
        labelRow.addView(label);

        TextView valueTv = new TextView(ctx);
        valueTv.setText(currentMs + " ms");
        valueTv.setTextSize(13);
        valueTv.setTextColor(CLR_ACCENT);
        valueTv.setTypeface(null, Typeface.BOLD);
        labelRow.addView(valueTv);

        row.addView(labelRow);

        SeekBar seekBar = new SeekBar(ctx);
        seekBar.setMax(2500);
        seekBar.setProgress(Math.max(0, currentMs - 100));
        seekBar.setPadding(0, (int)(6 * d), 0, 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int ms = progress + 100;
                valueTv.setText(ms + " ms");
                if (fromUser && cb != null) cb.onChange(ms);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        LinearLayout range = new LinearLayout(ctx);
        range.setOrientation(LinearLayout.HORIZONTAL);
        TextView low = new TextView(ctx); low.setText("100ms"); low.setTextSize(10); low.setTextColor(CLR_TEXT2);
        range.addView(low);
        TextView high = new TextView(ctx); high.setText("2600ms"); high.setTextSize(10); high.setTextColor(CLR_TEXT2);
        high.setGravity(Gravity.END);
        high.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        range.addView(high);

        row.addView(seekBar);
        row.addView(range);
        return row;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(CLR_CARD);
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(CLR_WHITE);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(CLR_TEXT);
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);
        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(CLR_TEXT2);
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);
        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static View infoRow(Context ctx, float d, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(CLR_WHITE);
        TextView lv = new TextView(ctx);
        lv.setText(label);
        lv.setTextSize(13);
        lv.setTextColor(CLR_TEXT);
        lv.setTypeface(null, Typeface.BOLD);
        row.addView(lv);
        TextView vv = new TextView(ctx);
        vv.setText(value);
        vv.setTextSize(12);
        vv.setTextColor(CLR_TEXT2);
        vv.setPadding(0, (int)(4 * d), 0, 0);
        row.addView(vv);
        return row;
    }

    private static View itemDivider(Context ctx, float d) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins((int)(14 * d), 0, (int)(14 * d), 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(CLR_DIV);
        return v;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(CLR_TEXT2);
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }

    public interface DelayCallback { void onChange(int ms); }
}
