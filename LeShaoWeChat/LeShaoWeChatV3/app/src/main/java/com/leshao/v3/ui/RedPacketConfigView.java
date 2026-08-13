package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.RedPacketHook;

import java.util.HashSet;
import java.util.Set;

public class RedPacketConfigView {

    private static final String KEY_ENABLED = "ls_redpacket_enabled";
    private static final String KEY_DELAY = "ls_redpacket_delay";
    private static final String KEY_PRIVATE = "ls_rp_private";
    private static final String KEY_GROUP = "ls_rp_group";
    private static final String KEY_TIME_ON = "ls_rp_time_on";
    private static final String KEY_TIME_START = "ls_rp_time_start";
    private static final String KEY_TIME_END = "ls_rp_time_end";
    private static final String KEY_KW_EXCLUDE_ON = "ls_rp_kw_exclude_on";
    private static final String KEY_KW_EXCLUDE = "ls_rp_kw_exclude";
    private static final String KEY_KW_INCLUDE_ON = "ls_rp_kw_include_on";
    private static final String KEY_KW_INCLUDE = "ls_rp_kw_include";
    private static final String KEY_FAST_PRIVATE = "ls_rp_fast_private";
    private static final String KEY_FAST_GROUP = "ls_rp_fast_group";
    private static final String KEY_TTS_ANNOUNCE = "ls_rp_tts_announce";

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ContextManager.getPrefs();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        boolean enabled = prefs != null && prefs.getBoolean(KEY_ENABLED, false);
        boolean priv = prefs != null && prefs.getBoolean(KEY_PRIVATE, true);
        boolean group = prefs != null && prefs.getBoolean(KEY_GROUP, false);
        boolean timeOn = prefs != null && prefs.getBoolean(KEY_TIME_ON, false);
        String timeStart = prefs != null ? prefs.getString(KEY_TIME_START, "00:00") : "00:00";
        String timeEnd = prefs != null ? prefs.getString(KEY_TIME_END, "06:00") : "06:00";
        boolean kwExOn = prefs != null && prefs.getBoolean(KEY_KW_EXCLUDE_ON, false);
        String kwEx = prefs != null ? prefs.getString(KEY_KW_EXCLUDE, "") : "";
        boolean kwInOn = prefs != null && prefs.getBoolean(KEY_KW_INCLUDE_ON, false);
        String kwIn = prefs != null ? prefs.getString(KEY_KW_INCLUDE, "") : "";
        String fastPriv = prefs != null ? prefs.getString(KEY_FAST_PRIVATE, "") : "";
        String fastGrp = prefs != null ? prefs.getString(KEY_FAST_GROUP, "") : "";
        boolean ttsOn = prefs != null && prefs.getBoolean(KEY_TTS_ANNOUNCE, true);
        int delayMs = prefs != null ? prefs.getInt(KEY_DELAY, 500) : 500;

        root.addView(sectionLabel(ctx, d, "核心开关"));
        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(switchRow(ctx, d, "自动秒抢红包", "检测到红包后自动点击按钮领取", enabled, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ENABLED, on).apply();
            RedPacketHook.setEnabled(on);
        }));
        root.addView(card1);

        root.addView(candyDivider(ctx, d));
        root.addView(sectionLabel(ctx, d, "领取范围"));
        LinearLayout card2 = makeCard(ctx, d);
        card2.addView(switchRow(ctx, d, "私聊红包", "自动领取私聊中的红包", priv, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_PRIVATE, on).apply();
            RedPacketHook.setPrivateEnabled(on);
        }));
        card2.addView(candyDivider(ctx, d));
        card2.addView(switchRow(ctx, d, "群聊红包", "自动领取群聊中的红包", group, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_GROUP, on).apply();
            RedPacketHook.setGroupEnabled(on);
        }));
        root.addView(card2);

        root.addView(candyDivider(ctx, d));
        root.addView(sectionLabel(ctx, d, "时间段过滤"));
        LinearLayout card3 = makeCard(ctx, d);
        card3.addView(switchRow(ctx, d, "开启时间段过滤", "在指定时间段内不领取红包", timeOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_TIME_ON, on).apply();
            RedPacketHook.setTimeFilter(on, timeStart, timeEnd);
        }));
        card3.addView(candyDivider(ctx, d));
        card3.addView(timeRangeRow(ctx, d, timeStart, timeEnd, (s, e) -> {
            if (prefs != null) {
                prefs.edit().putString(KEY_TIME_START, s).putString(KEY_TIME_END, e).apply();
            }
            RedPacketHook.setTimeFilter(prefs != null && prefs.getBoolean(KEY_TIME_ON, false), s, e);
        }));
        root.addView(card3);

        root.addView(candyDivider(ctx, d));
        root.addView(sectionLabel(ctx, d, "关键词过滤"));
        LinearLayout card4 = makeCard(ctx, d);
        card4.addView(switchRow(ctx, d, "排除关键词", "包含这些关键词的红包不领取", kwExOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_KW_EXCLUDE_ON, on).apply();
            Set<String> set = parseKeywords(prefs != null ? prefs.getString(KEY_KW_EXCLUDE, "") : "");
            RedPacketHook.setKeywordExclude(on, set);
        }));
        card4.addView(candyDivider(ctx, d));
        card4.addView(keywordRow(ctx, d, "排除关键词(空格分隔)", kwEx, val -> {
            if (prefs != null) prefs.edit().putString(KEY_KW_EXCLUDE, val).apply();
            Set<String> set = parseKeywords(val);
            RedPacketHook.setKeywordExclude(prefs != null && prefs.getBoolean(KEY_KW_EXCLUDE_ON, false), set);
        }));
        card4.addView(candyDivider(ctx, d));
        card4.addView(switchRow(ctx, d, "包含关键词", "只领取包含这些关键词的红包", kwInOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_KW_INCLUDE_ON, on).apply();
            Set<String> set = parseKeywords(prefs != null ? prefs.getString(KEY_KW_INCLUDE, "") : "");
            RedPacketHook.setKeywordInclude(on, set);
        }));
        card4.addView(candyDivider(ctx, d));
        card4.addView(keywordRow(ctx, d, "包含关键词(空格分隔)", kwIn, val -> {
            if (prefs != null) prefs.edit().putString(KEY_KW_INCLUDE, val).apply();
            Set<String> set = parseKeywords(val);
            RedPacketHook.setKeywordInclude(prefs != null && prefs.getBoolean(KEY_KW_INCLUDE_ON, false), set);
        }));
        root.addView(card4);

        root.addView(candyDivider(ctx, d));
        root.addView(sectionLabel(ctx, d, "秒抢名单"));
        LinearLayout card5 = makeCard(ctx, d);
        card5.addView(pickerRow(ctx, d, parentAct, "好友秒抢白名单", "指定的好友红包立即秒抢，无延迟", fastPriv,
            ContactPickerDialog.MODE_FRIEND, val -> {
                if (prefs != null) prefs.edit().putString(KEY_FAST_PRIVATE, val).apply();
                RedPacketHook.setFastPrivateWxids(parseIds(val));
            }));
        card5.addView(candyDivider(ctx, d));
        card5.addView(pickerRow(ctx, d, parentAct, "群聊秒抢白名单", "指定的群聊红包立即秒抢，无延迟", fastGrp,
            ContactPickerDialog.MODE_GROUP, val -> {
                if (prefs != null) prefs.edit().putString(KEY_FAST_GROUP, val).apply();
                RedPacketHook.setFastGroupIds(parseIds(val));
            }));
        root.addView(card5);

        root.addView(candyDivider(ctx, d));
        root.addView(sectionLabel(ctx, d, "播报设置"));
        LinearLayout card6 = makeCard(ctx, d);
        card6.addView(switchRow(ctx, d, "抢到红包播报金额", "通过TTS语音播报抢到的金额", ttsOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_TTS_ANNOUNCE, on).apply();
            RedPacketHook.setTtsAnnounce(on);
        }));
        root.addView(card6);

        root.addView(candyDivider(ctx, d));
        root.addView(sectionLabel(ctx, d, "延迟设置"));
        LinearLayout card7 = makeCard(ctx, d);
        card7.addView(delayRow(ctx, d, delayMs, ms -> {
            if (prefs != null) prefs.edit().putInt(KEY_DELAY, ms).apply();
        }));
        root.addView(card7);

        return root;
    }

    private static View timeRangeRow(Context ctx, float d, String start, String end, TimeCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView label = new TextView(ctx);
        label.setText("时间段:  ");
        label.setTextSize(13);
        label.setTextColor(AppColors.text1());
        row.addView(label);

        EditText etStart = new EditText(ctx);
        etStart.setText(start);
        etStart.setTextSize(13);
        etStart.setTextColor(AppColors.text1());
        etStart.setSingleLine(true);
        etStart.setInputType(InputType.TYPE_CLASS_DATETIME);
        etStart.setWidth((int)(60 * d));
        etStart.setPadding((int)(4 * d), (int)(4 * d), (int)(4 * d), (int)(4 * d));
        etStart.setBackgroundColor(AppColors.bg());
        row.addView(etStart);

        TextView sep = new TextView(ctx);
        sep.setText(" ~ ");
        sep.setTextSize(13);
        sep.setTextColor(AppColors.text2());
        row.addView(sep);

        EditText etEnd = new EditText(ctx);
        etEnd.setText(end);
        etEnd.setTextSize(13);
        etEnd.setTextColor(AppColors.text1());
        etEnd.setSingleLine(true);
        etEnd.setInputType(InputType.TYPE_CLASS_DATETIME);
        etEnd.setWidth((int)(60 * d));
        etEnd.setPadding((int)(4 * d), (int)(4 * d), (int)(4 * d), (int)(4 * d));
        etEnd.setBackgroundColor(AppColors.bg());
        row.addView(etEnd);

        TextView save = new TextView(ctx);
        save.setText("确定");
        save.setTextSize(12);
        save.setTextColor(AppColors.accent());
        save.setPadding((int)(8 * d), (int)(4 * d), 0, (int)(4 * d));
        save.setOnClickListener(v -> {
            String s = etStart.getText().toString().trim();
            String e = etEnd.getText().toString().trim();
            if (!s.isEmpty() && !e.isEmpty() && cb != null) cb.onChange(s, e);
        });
        row.addView(save);

        return row;
    }

    private static View keywordRow(Context ctx, float d, String hint, String value, StringCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int)(14 * d), (int)(10 * d), (int)(14 * d), (int)(10 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(AppColors.text2());
        et.setText(value);
        et.setTextSize(13);
        et.setTextColor(AppColors.text1());
        et.setSingleLine(false);
        et.setMinLines(1);
        et.setMaxLines(3);
        et.setPadding((int)(4 * d), (int)(4 * d), (int)(4 * d), (int)(4 * d));
        et.setBackgroundColor(AppColors.bg());
        row.addView(et);

        TextView save = new TextView(ctx);
        save.setText("保存关键词");
        save.setTextSize(12);
        save.setTextColor(AppColors.accent());
        save.setPadding(0, (int)(4 * d), 0, 0);
        save.setOnClickListener(v -> {
            if (cb != null) cb.onChange(et.getText().toString().trim());
        });
        row.addView(save);

        return row;
    }

    private static View pickerRow(Context ctx, float d, Activity parentAct, String title,
                                   String desc, String current, int mode, StringCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(11);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }

        int count = 0;
        if (current != null && !current.isEmpty()) {
            for (String id : current.split(",")) {
                if (id != null && !id.trim().isEmpty()) count++;
            }
        }
        TextView cntTv = new TextView(ctx);
        cntTv.setText(count > 0 ? "已选 " + count + " 个" : "点击选择");
        cntTv.setTextSize(12);
        cntTv.setTextColor(count > 0 ? AppColors.accent() : AppColors.text2());
        cntTv.setPadding(0, (int)(3 * d), 0, 0);
        textCol.addView(cntTv);

        row.addView(textCol);

        row.setOnClickListener(v -> {
            ContactPickerDialog.show(parentAct, current, mode, (selected, display) -> {
                cntTv.setText("已选 " + selected.size() + " 个");
                cntTv.setTextColor(AppColors.accent());
                if (cb != null) cb.onChange(String.join(",", selected));
            });
        });

        return row;
    }

    private static View delayRow(Context ctx, float d, int currentMs, RedPacketConfigView.DelayCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout labelRow = new LinearLayout(ctx);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView label = new TextView(ctx);
        label.setText("延迟时间: ");
        label.setTextSize(13);
        label.setTextColor(AppColors.text1());
        labelRow.addView(label);

        TextView valueTv = new TextView(ctx);
        valueTv.setText(currentMs + " ms");
        valueTv.setTextSize(13);
        valueTv.setTextColor(AppColors.accent());
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
        TextView low = new TextView(ctx); low.setText("100ms"); low.setTextSize(10); low.setTextColor(AppColors.text2());
        range.addView(low);
        TextView high = new TextView(ctx); high.setText("2600ms"); high.setTextSize(10); high.setTextColor(AppColors.text2());
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
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);
        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);
        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        styleSwitch(sw, checked, ctx);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static View itemDivider(Context ctx, float d) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins((int)(14 * d), 0, (int)(14 * d), 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(AppColors.divider());
        return v;
    }

    private static TextView sectionLabel(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View spacerV(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }

    private static void styleSwitch(android.widget.Switch sw, boolean checked, Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        if (checked) {
            track.setColors(new int[]{AppColors.accent2(), AppColors.accent(), AppColors.accent2(), AppColors.accent(), AppColors.accent2()});
            track.setStroke((int)(2 * d), AppColors.accent());
        } else {
            track.setColor(AppColors.bg());
            track.setStroke((int)(2 * d), AppColors.divider());
        }
        float rT = d * 10, rB = d * 18;
        try { track.setCornerRadii(new float[]{rT, rT, rT, rT, rB, rB, rB, rB}); }
        catch (Throwable e) { track.setCornerRadius(d * 14); }
        sw.setTrackDrawable(track);
        sw.setThumbTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{AppColors.accent(), AppColors.arrow()}));
    }

    private static boolean isDark() { return AppColors.isDarkMode(); }

    private static Set<String> parseKeywords(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) return set;
        for (String kw : raw.split("\\s+")) {
            if (!kw.trim().isEmpty()) set.add(kw.trim());
        }
        return set;
    }

    private static Set<String> parseIds(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null || raw.trim().isEmpty()) return set;
        for (String id : raw.split(",")) {
            if (!id.trim().isEmpty()) set.add(id.trim());
        }
        return set;
    }

    public interface DelayCallback { void onChange(int ms); }
    public interface TimeCallback { void onChange(String start, String end); }
    public interface StringCallback { void onChange(String value); }

    private static View candyDivider(Context ctx, float d) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.candyPink(), AppColors.candyYellow(), AppColors.accent(), AppColors.candyPink()});
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(1.5f * d));
        lp.setMargins((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(6 * d));
        v.setLayoutParams(lp);
        v.setBackground(gd);
        return v;
    }
}
