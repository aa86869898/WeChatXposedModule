package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.graphics.Typeface;
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
import com.leshao.v3.service.TTSBroadcaster;

import java.util.HashSet;
import java.util.Set;

public class TTSPageView {

    private static final String KEY_ANNOUNCE_TEXT = "ls_announce_text";
    private static final String KEY_ANNOUNCE_IMAGE = "ls_announce_image";
    private static final String KEY_ANNOUNCE_VIDEO = "ls_announce_video";
    private static final String KEY_ANNOUNCE_LOCATION = "ls_announce_location";
    private static final String KEY_ANNOUNCE_REDBAG = "ls_announce_redbag";
    private static final String KEY_ANNOUNCE_TRANSFER = "ls_announce_transfer";
    private static final String KEY_ANNOUNCE_CARD = "ls_announce_card";
    private static final String KEY_ANNOUNCE_FILE = "ls_announce_file";
    private static final String KEY_ANNOUNCE_STICKER = "ls_announce_sticker";
    private static final String KEY_ANNOUNCE_CALL = "ls_announce_call";
    private static final String KEY_ANNOUNCE_NICKNAME = "ls_announce_nickname";
    private static final String KEY_ANNOUNCE_GROUP = "ls_announce_group";
    private static final String KEY_QUIET_ON = "ls_quiet_enabled";
    private static final String KEY_QUIET_START = "ls_quiet_start";
    private static final String KEY_QUIET_END = "ls_quiet_end";
    private static final String KEY_ANNOUNCE_WL = "ls_tts_whitelist";
    private static final String KEY_ANNOUNCE_INTERVAL = "ls_announce_interval_ms";
    private static final String KEY_TEXT_CUTOFF = "ls_text_truncate_len";
    private static final String KEY_TEXT_TRUNCATE = "ls_text_truncate";

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ContextManager.getPrefs();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16 * d), (int)(16 * d), (int)(16 * d), (int)(16 * d));

        boolean announceText = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_TEXT, true);
        boolean announceImage = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_IMAGE, true);
        boolean announceVideo = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_VIDEO, true);
        boolean announceLocation = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_LOCATION, true);
        boolean announceRedBag = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_REDBAG, true);
        boolean announceTransfer = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_TRANSFER, true);
        boolean announceCard = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CARD, true);
        boolean announceFile = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_FILE, true);
        boolean announceSticker = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_STICKER, false);
        boolean announceCall = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CALL, true);
        boolean announceNickname = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_NICKNAME, true);
        boolean announceGroup = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_GROUP, false);
        boolean quietOn = prefs != null && prefs.getBoolean(KEY_QUIET_ON, false);
        String quietStart = prefs != null ? prefs.getString(KEY_QUIET_START, "23:00") : "23:00";
        String quietEnd = prefs != null ? prefs.getString(KEY_QUIET_END, "07:00") : "07:00";
        String whitelist = prefs != null ? prefs.getString(KEY_ANNOUNCE_WL, "") : "";
        int interval = prefs != null ? Integer.parseInt(prefs.getString(KEY_ANNOUNCE_INTERVAL, "0")) : 0;
        boolean truncate = prefs != null && prefs.getBoolean(KEY_TEXT_TRUNCATE, true);
        int cutoff = prefs != null ? Integer.parseInt(prefs.getString(KEY_TEXT_CUTOFF, "150")) : 150;

        root.addView(sectionLabel(ctx, d, "消息播报类型"));
        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(switchRow(ctx, d, "文字消息播报", "格式: 某某某说:XXX内容", announceText, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_TEXT, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "语音消息播报", "格式: 某某某说:开始播放语音", announceCall, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_CALL, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "图片消息播报", "格式: 某某某发来一张照片", announceImage, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_IMAGE, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "视频消息播报", "格式: 某某某发来一段视频", announceVideo, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_VIDEO, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "位置消息播报", "格式: 某某某发来定位在:位置详细内容", announceLocation, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_LOCATION, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "红包消息播报", "播报红包消息", announceRedBag, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_REDBAG, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "转账消息播报", "播报转账消息", announceTransfer, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_TRANSFER, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "名片消息播报", "播报名片消息", announceCard, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_CARD, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "文件消息播报", "播报文件消息", announceFile, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_FILE, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "表情消息播报", "播报表情消息", announceSticker, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_STICKER, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "播报发送人昵称", "播报消息发送人昵称", announceNickname, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_NICKNAME, on).apply();
        }));
        root.addView(card1);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, d, "群聊播报"));
        LinearLayout card2 = makeCard(ctx, d);
        card2.addView(switchRow(ctx, d, "播报群聊消息", "打开后格式: XX群的XXX发来:XXX内容", announceGroup, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_GROUP, on).apply();
        }));
        root.addView(card2);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, d, "自定义播报名单"));
        LinearLayout card3 = makeCard(ctx, d);
        card3.addView(pickerRow(ctx, d, parentAct, "播报白名单", "只播报指定好友或群聊的消息", whitelist,
            ContactPickerDialog.MODE_FRIEND, val -> {
                if (prefs != null) prefs.edit().putString(KEY_ANNOUNCE_WL, val).apply();
            }));
        root.addView(card3);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, d, "免打扰时段"));
        LinearLayout card4 = makeCard(ctx, d);
        card4.addView(switchRow(ctx, d, "开启免打扰", "在指定时段内不播报消息", quietOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_QUIET_ON, on).apply();
        }));
        card4.addView(itemDivider(ctx, d));
        card4.addView(timeRangeRow(ctx, d, quietStart, quietEnd, (s, e) -> {
            if (prefs != null) {
                prefs.edit().putString(KEY_QUIET_START, s).putString(KEY_QUIET_END, e).apply();
            }
        }));
        root.addView(card4);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, d, "播报设置"));
        LinearLayout card5 = makeCard(ctx, d);
        card5.addView(switchRow(ctx, d, "截断长文字", "超长文字自动截断后播报", truncate, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_TEXT_TRUNCATE, on).apply();
        }));
        card5.addView(itemDivider(ctx, d));
        card5.addView(intervalRow(ctx, d, cutoff, "截断长度", "超过此长度的文字将被截断", 1, 500, val -> {
            if (prefs != null) prefs.edit().putString(KEY_TEXT_CUTOFF, String.valueOf(val)).apply();
        }));
        card5.addView(itemDivider(ctx, d));
        card5.addView(intervalRow(ctx, d, interval, "播报间隔", "两次播报之间最小间隔(毫秒)", 0, 5000, val -> {
            if (prefs != null) prefs.edit().putString(KEY_ANNOUNCE_INTERVAL, String.valueOf(val)).apply();
        }));
        root.addView(card5);

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
        etStart.setBackgroundColor(isDark() ? 0xFF3A3A50 : 0xFFF0F0F0);
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
        etEnd.setBackgroundColor(isDark() ? 0xFF3A3A50 : 0xFFF0F0F0);
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

    private static View intervalRow(Context ctx, float d, int current, String label,
                                     String desc, int min, int max, IntCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout labelRow = new LinearLayout(ctx);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView lv = new TextView(ctx);
        lv.setText(label + (desc != null ? " (" + desc + ")" : "") + ": ");
        lv.setTextSize(12);
        lv.setTextColor(AppColors.text2());
        labelRow.addView(lv);

        TextView valueTv = new TextView(ctx);
        valueTv.setText(String.valueOf(current));
        valueTv.setTextSize(14);
        valueTv.setTextColor(AppColors.accent());
        valueTv.setTypeface(null, Typeface.BOLD);
        labelRow.addView(valueTv);

        row.addView(labelRow);

        SeekBar seekBar = new SeekBar(ctx);
        seekBar.setMax(max - min);
        seekBar.setProgress(Math.max(0, current - min));
        seekBar.setPadding(0, (int)(4 * d), 0, 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = progress + min;
                valueTv.setText(String.valueOf(val));
                if (fromUser && cb != null) cb.onChange(val);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        LinearLayout range = new LinearLayout(ctx);
        range.setOrientation(LinearLayout.HORIZONTAL);
        TextView low = new TextView(ctx); low.setText(String.valueOf(min)); low.setTextSize(10); low.setTextColor(AppColors.text2());
        range.addView(low);
        TextView high = new TextView(ctx); high.setText(String.valueOf(max)); high.setTextSize(10); high.setTextColor(AppColors.text2());
        high.setGravity(Gravity.END);
        high.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        range.addView(high);

        row.addView(seekBar);
        row.addView(range);
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

    public interface TimeCallback { void onChange(String start, String end); }
    public interface IntCallback { void onChange(int value); }
    public interface StringCallback { void onChange(String value); }
}
