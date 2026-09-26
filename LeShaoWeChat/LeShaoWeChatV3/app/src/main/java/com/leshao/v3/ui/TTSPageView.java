package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.SeekBar;
import android.widget.Switch;
import com.leshao.v3.ContextManager;
import com.leshao.v3.service.TTSBroadcaster;
import com.leshao.v3.wm.utils.WmPrefs;
import com.leshao.v3.ui.widgets.M3Page;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

public class TTSPageView {

    private static Dialog sTtsCubeDialog = null;

    private static final String KEY_ANNOUNCE_TEXT = "ls_announce_text";
    private static final String KEY_ANNOUNCE_IMAGE = "ls_announce_image";
    private static final String KEY_ANNOUNCE_VIDEO = "ls_announce_video";
    private static final String KEY_ANNOUNCE_LOCATION = "ls_announce_location";
    private static final String KEY_ANNOUNCE_CARD = "ls_announce_card";
    private static final String KEY_ANNOUNCE_FILE = "ls_announce_file";
    private static final String KEY_ANNOUNCE_STICKER = "ls_announce_sticker";
    private static final String KEY_ANNOUNCE_CALL = "ls_announce_call";
    private static final String KEY_ANNOUNCE_QUOTE = "ls_announce_quote";
    private static final String KEY_ANNOUNCE_MINIPROGRAM = "ls_announce_miniprogram";
    private static final String KEY_ANNOUNCE_VIDEOCHANNEL = "ls_announce_videochannel";
    private static final String KEY_ANNOUNCE_CHATHISTORY = "ls_announce_chathistory";
    private static final String KEY_ANNOUNCE_NICKNAME = "ls_announce_nickname";
    private static final String KEY_ANNOUNCE_GROUP = "ls_announce_group";
    private static final String KEY_QUIET_ON = "ls_quiet_enabled";
    private static final String KEY_QUIET_START = "ls_quiet_start";
    private static final String KEY_QUIET_END = "ls_quiet_end";
    private static final String KEY_ANNOUNCE_WL = "ls_tts_whitelist";
    private static final String KEY_ANNOUNCE_INTERVAL = "ls_announce_interval_ms";
    private static final String KEY_TEXT_CUTOFF = "ls_text_truncate_len";
    private static final String KEY_TEXT_TRUNCATE = "ls_text_truncate";
    private static final String KEY_ANNOUNCE_PAT = "ls_announce_pat";
    private static final String KEY_ANNOUNCE_AT = "ls_announce_at";
    private static final String KEY_ANNOUNCE_BL = "ls_tts_blacklist";
    private static final String KEY_TTS_COMMAND = "ls_tts_command";

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ContextManager.getPrefs();

        // 外层 ScrollView 包裹
        ScrollView scrollView = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(AppColors.SPACE_LG_DP * d), (int)(AppColors.SPACE_MD_DP * d),
                (int)(AppColors.SPACE_LG_DP * d), (int)(AppColors.SPACE_XL_DP * d));

        // ★ TTS 引擎选择 + 配音魔方入口 (置顶)
        root.addView(buildTtsEngineCard(ctx, parentAct, d, prefs));
        root.addView(candyDivider(ctx, d));

boolean announceText = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_TEXT, true);
        boolean announceImage = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_IMAGE, true);
        boolean announceVideo = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_VIDEO, true);
        boolean announceLocation = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_LOCATION, true);
        boolean announceCard = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CARD, true);
        boolean announceFile = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_FILE, true);
        boolean announceSticker = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_STICKER, false);
        boolean announceCall = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CALL, true);
        boolean announceQuote = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_QUOTE, true);
        boolean announceMiniProgram = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_MINIPROGRAM, true);
        boolean announceVideoChannel = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_VIDEOCHANNEL, true);
        boolean announceChatHistory = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_CHATHISTORY, true);
        boolean announceNickname = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_NICKNAME, true);
        boolean announceGroup = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_GROUP, false);
        boolean announcePat = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_PAT, false);
        boolean announceAt = prefs != null && prefs.getBoolean(KEY_ANNOUNCE_AT, true);
        boolean quietOn = prefs != null && prefs.getBoolean(KEY_QUIET_ON, false);
        String quietStart = prefs != null ? prefs.getString(KEY_QUIET_START, "23:00") : "23:00";
        String quietEnd = prefs != null ? prefs.getString(KEY_QUIET_END, "07:00") : "07:00";
        String whitelist = prefs != null ? prefs.getString(KEY_ANNOUNCE_WL, "") : "";
        String blacklist = prefs != null ? prefs.getString(KEY_ANNOUNCE_BL, "") : "";
        int interval = prefs != null ? Integer.parseInt(prefs.getString(KEY_ANNOUNCE_INTERVAL, "0")) : 0;
        boolean truncate = prefs != null && prefs.getBoolean(KEY_TEXT_TRUNCATE, true);
        int cutoff = prefs != null ? Integer.parseInt(prefs.getString(KEY_TEXT_CUTOFF, "150")) : 150;
        float speechRate = prefs != null ? prefs.getFloat("ls_speech_rate", 1.1f) : 1.1f;
        boolean ttsCommand = prefs != null && prefs.getBoolean(KEY_TTS_COMMAND, false);

        // 启用 #tts 文字转语音指令
        LinearLayout cardTts = makeCard(ctx, d);
        cardTts.addView(switchRow(ctx, d, "\u542f\u7528 #tts \u6587\u5b57\u8f6c\u8bed\u97f3\u6307\u4ee4", "\u5728\u804a\u5929\u7a97\u53e3\u53d1\u9001 #tts XXX\u5185\u5bb9, \u81ea\u52a8\u5c06\u6587\u5b57\u5408\u6210\u8bed\u97f3\u6d88\u606f\u53d1\u51fa", ttsCommand, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_TTS_COMMAND, on).apply();
        }));
        root.addView(cardTts);

        root.addView(candyDivider(ctx, d));

        // 语音消息自动播放 (VoiceAutoPlay 读取 wm_prefs 的 auto_voice)
        boolean autoVoice = WmPrefs.isAutoVoice();
        LinearLayout cardAutoVoice = makeCard(ctx, d);
        cardAutoVoice.addView(switchRow(ctx, d, "自动播放语音消息", "收到语音消息时自动转文字并播报(需播报白名单)",
                autoVoice, (v, on) -> WmPrefs.set("auto_voice", on)));
        root.addView(cardAutoVoice);

        // 方案7: 双模式开关 — 语音发送音质: 人声增强(v928, 音乐伴奏更清晰) vs 原音还原(v929, 默认保真)
        boolean voiceEnhance = WmPrefs.get("voice_enhance", false);
        LinearLayout cardVoiceMode = makeCard(ctx, d);
        cardVoiceMode.addView(switchRow(ctx, d, "语音发送人声增强", "开启=人声增强链(高通+EQ+压缩, 音乐带伴奏人声更突出); 关闭=原音还原链(透明处理, 保真优先, 默认)",
                voiceEnhance, (v, on) -> WmPrefs.set("voice_enhance", on)));
        root.addView(cardVoiceMode);

        // v1085: 文字转语音误报语音时长 — 超过 60 秒的语音按指定秒数误报, 保证语音能发出
        boolean falseDurOn = WmPrefs.get("ls_tts_false_dur_on", true);
        int falseDurSec = WmPrefs.getInt("ls_tts_false_dur_sec", 60);
        LinearLayout cardFalseDur = makeCard(ctx, d);
        cardFalseDur.addView(switchRow(ctx, d, "文字转语音误报语音时长",
                "开启后超过 60 秒的语音按下方秒数误报时长(默认 60 秒); 关闭则按真实时长上报",
                falseDurOn, (v, on) -> WmPrefs.set("ls_tts_false_dur_on", on)));
        cardFalseDur.addView(itemDivider(ctx, d));
        cardFalseDur.addView(numberInputRow(ctx, d, "误报时长", "超过 60 秒时上报的语音秒数",
                String.valueOf(falseDurSec), "秒", sec -> WmPrefs.setInt("ls_tts_false_dur_sec",
                        sec <= 0 ? 60 : Math.min(sec, 3600))));
        root.addView(cardFalseDur);

        root.addView(candyDivider(ctx, d));
        root.addView(sectionLabel(ctx, d, "\u81ea\u52a8\u64ad\u62a5\u7c7b\u578b"));

        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(switchRow(ctx, d, "\u6587\u5b57\u6d88\u606f\u64ad\u62a5", null, announceText, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_TEXT, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u8bed\u97f3\u6d88\u606f\u64ad\u62a5", null, announceCall, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_CALL, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u56fe\u7247\u6d88\u606f\u64ad\u62a5", null, announceImage, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_IMAGE, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u89c6\u9891\u6d88\u606f\u64ad\u62a5", null, announceVideo, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_VIDEO, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u4f4d\u7f6e\u6d88\u606f\u64ad\u62a5", null, announceLocation, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_LOCATION, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u540d\u7247\u6d88\u606f\u64ad\u62a5", null, announceCard, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_CARD, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u6587\u4ef6\u6d88\u606f\u64ad\u62a5", null, announceFile, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_FILE, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u8868\u60c5\u6d88\u606f\u64ad\u62a5", null, announceSticker, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_STICKER, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u5f15\u7528\u6d88\u606f\u64ad\u62a5", null, announceQuote, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_QUOTE, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u804a\u5929\u8bb0\u5f55\u64ad\u62a5", null, announceChatHistory, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_CHATHISTORY, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u88ab\u62cd\u81ea\u52a8\u64ad\u62a5", null, announcePat, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_PAT, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u7fa4\u5185\u88ab@\u65f6\u64ad\u62a5", null, announceAt, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_AT, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u5c0f\u7a0b\u5e8f\u6d88\u606f\u64ad\u62a5", null, announceMiniProgram, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_MINIPROGRAM, on).apply();
        }));
        card1.addView(itemDivider(ctx, d));
        card1.addView(switchRow(ctx, d, "\u89c6\u9891\u53f7\u6d88\u606f\u64ad\u62a5", null, announceVideoChannel, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_VIDEOCHANNEL, on).apply();
        }));
        root.addView(card1);

        root.addView(candyDivider(ctx, d));
        root.addView(sectionLabel(ctx, d, "\u81ea\u52a8\u64ad\u62a5\u89c4\u5219"));

        LinearLayout card2 = makeCard(ctx, d);
        card2.addView(switchRow(ctx, d, "\u662f\u5426\u64ad\u62a5\u5168\u7fa4\u6d88\u606f", null, announceGroup, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_GROUP, on).apply();
        }));
        card2.addView(itemDivider(ctx, d));
        card2.addView(switchRow(ctx, d, "\u662f\u5426\u64ad\u62a5\u53d1\u9001\u4eba\u6635\u79f0/\u5907\u6ce8", null, announceNickname, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_ANNOUNCE_NICKNAME, on).apply();
        }));
        root.addView(card2);

        root.addView(candyDivider(ctx, d));
        LinearLayout card3 = makeCard(ctx, d);
        boolean wlStrict = prefs != null && prefs.getBoolean("ls_tts_whitelist_strict", true);
        card3.addView(switchRow(ctx, d, "\u64ad\u62a5\u767d\u540d\u5355\u4e25\u683c\u6a21\u5f0f", "\u5f00: \u767d\u540d\u5355\u4e3a\u7a7a\u65f6\u4e0d\u64ad\u62a5\u4efb\u4f55\u6d88\u606f; \u5173: \u767d\u540d\u5355\u4e3a\u7a7a\u65f6\u5168\u90e8\u64ad\u62a5", wlStrict, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean("ls_tts_whitelist_strict", on).apply();
        }));
        card3.addView(itemDivider(ctx, d));
        card3.addView(pickerRow(ctx, d, parentAct, "\u81ea\u52a8\u64ad\u62a5\u767d\u540d\u5355\u5217\u8868", "\u53ea\u64ad\u62a5\u6307\u5b9a\u597d\u53cb\u6216\u7fa4\u804a\u7684\u6d88\u606f", whitelist,
            ContactPickerDialog.MODE_FRIEND, val -> {
                if (prefs != null) prefs.edit().putString(KEY_ANNOUNCE_WL, val).apply();
            }));
        card3.addView(itemDivider(ctx, d));
        card3.addView(pickerRow(ctx, d, parentAct, "\u81ea\u52a8\u64ad\u62a5\u9ed1\u540d\u5355\u5217\u8868", "\u4e0d\u64ad\u62a5\u6307\u5b9a\u597d\u53cb\u6216\u7fa4\u804a\u7684\u6d88\u606f", blacklist,
            ContactPickerDialog.MODE_FRIEND, val -> {
                if (prefs != null) prefs.edit().putString(KEY_ANNOUNCE_BL, val).apply();
            }));
        // v955: 白名单生效状态警示(修复"TTS播报无效"实为白名单严格过滤的用户困惑)
        card3.addView(itemDivider(ctx, d));
        card3.addView(buildWhitelistStatusRow(ctx, d, whitelist, wlStrict));
        root.addView(card3);

        root.addView(candyDivider(ctx, d));
        LinearLayout card4 = makeCard(ctx, d);
        card4.addView(switchRow(ctx, d, "\u4ec5\u5728\u65f6\u95f4\u6bb5\u5185\u81ea\u52a8\u64ad\u62a5", "\u5728\u6307\u5b9a\u65f6\u6bb5\u5185\u4e0d\u64ad\u62a5\u6d88\u606f", quietOn, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_QUIET_ON, on).apply();
        }));
        card4.addView(itemDivider(ctx, d));
        card4.addView(timeRangeRow(ctx, d, quietStart, quietEnd, (s, e) -> {
            if (prefs != null) {
                prefs.edit().putString(KEY_QUIET_START, s).putString(KEY_QUIET_END, e).apply();
            }
        }));
        root.addView(card4);

        root.addView(candyDivider(ctx, d));
        LinearLayout card5 = makeCard(ctx, d);
        card5.addView(switchRow(ctx, d, "\u5355\u6761\u6d88\u606f\u64ad\u62a5\u5b57\u7b26\u4e0a\u9650\u8bbe\u7f6e", "\u8d85\u8fc7\u6b64\u957f\u5ea6\u7684\u6587\u5b57\u5c06\u88ab\u622a\u65ad", truncate, (v, on) -> {
            if (prefs != null) prefs.edit().putBoolean(KEY_TEXT_TRUNCATE, on).apply();
        }));
        card5.addView(itemDivider(ctx, d));
        card5.addView(intervalRow(ctx, d, cutoff, "\u622a\u65ad\u957f\u5ea6", "\u8d85\u8fc7\u6b64\u957f\u5ea6\u7684\u6587\u5b57\u5c06\u88ab\u622a\u65ad", 1, 500, val -> {
            if (prefs != null) prefs.edit().putString(KEY_TEXT_CUTOFF, String.valueOf(val)).apply();
        }));
        card5.addView(itemDivider(ctx, d));
        card5.addView(intervalRow(ctx, d, interval, "\u64ad\u62a5\u95f4\u9694", "\u4e24\u6b21\u64ad\u62a5\u4e4b\u95f4\u6700\u5c0f\u95f4\u9694(\u6beb\u79d2)", 0, 5000, val -> {
            if (prefs != null) prefs.edit().putString(KEY_ANNOUNCE_INTERVAL, String.valueOf(val)).apply();
        }));
        card5.addView(itemDivider(ctx, d));
        card5.addView(speedRateRow(ctx, d, speechRate, rate -> {
            TTSBroadcaster.setSpeechRate(rate);
            if (prefs != null) prefs.edit().putFloat("ls_speech_rate", rate).apply();
        }));
        root.addView(card5);

        scrollView.addView(root);
        return scrollView;
    }

    /** v955: 白名单生效状态警示行 — 白名单非空且严格模式时醒目提示拦截范围 */
    private static View buildWhitelistStatusRow(Context ctx, float d, String whitelist, boolean strict) {
        int wlCount = 0;
        if (whitelist != null && !whitelist.trim().isEmpty()) {
            wlCount = whitelist.split("[,，]").length;
        }
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView status = new TextView(ctx);
        status.setTextSize(13);
        status.setSingleLine(false);

        if (wlCount == 0) {
            // 白名单为空: 严格模式下全静音(与 FilterManager 逻辑对应)
            if (strict) {
                status.setText("⚠️ 当前状态：白名单为空 + 严格模式开启 → 所有消息都不会播报！请添加白名单或关闭严格模式");
                status.setTextColor(AppColors.error());
            } else {
                status.setText("✅ 当前状态：白名单为空 + 严格模式关闭 → 全部消息播报");
                status.setTextColor(AppColors.onSurfaceVariant());
            }
        } else if (strict) {
            status.setText("⚠️ 当前状态：仅播报白名单内 " + wlCount + " 个会话，其他一切消息将被拦截（如收不到播报请检查此处）");
            status.setTextColor(AppColors.warning());
        } else {
            status.setText("✅ 当前状态：白名单 " + wlCount + " 个会话优先播报，其他会话也播报（非严格模式）");
            status.setTextColor(AppColors.onSurfaceVariant());
        }
        row.addView(status);
        return row;
    }

    private static View timeRangeRow(Context ctx, float d, String start, String end, TimeCallback cb) {        LinearLayout row = new LinearLayout(ctx);
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
        etStart.setInputType(InputType.TYPE_CLASS_TEXT);
        etStart.setWidth((int)(80 * d));
        etStart.setPadding((int)(4 * d), (int)(4 * d), (int)(4 * d), (int)(4 * d));
        etStart.setBackgroundColor(AppColors.card());
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
        etEnd.setInputType(InputType.TYPE_CLASS_TEXT);
        etEnd.setWidth((int)(80 * d));
        etEnd.setPadding((int)(4 * d), (int)(4 * d), (int)(4 * d), (int)(4 * d));
        etEnd.setBackgroundColor(AppColors.card());
        row.addView(etEnd);

        TextView save = new TextView(ctx);
        save.setText("确定");
        save.setTextSize(12);
        save.setTextColor(AppColors.accent());
        save.setPadding((int)(8 * d), (int)(4 * d), 0, (int)(4 * d));
        CandyUi.ripple(save, AppColors.SHAPE_FULL_DP);
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

        SeekBar seekBar = M3Page.slider(ctx);
        int span = Math.max(1, max - min);
        seekBar.setMax(span);
        seekBar.setProgress(Math.max(0, Math.min(span, current - min)));
        seekBar.setPadding(0, (int)(4 * d), 0, 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int val = progress + min;
                valueTv.setText(String.valueOf(val));
                if (fromUser && cb != null) cb.onChange(val);
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override public void onStopTrackingTouch(SeekBar sb) {
            }
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

        CandyUi.ripple(row, AppColors.SHAPE_MD_DP);
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
        Switch sw = CandyUi.newSwitch(ctx);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static LinearLayout numberInputRow(Context ctx, float d, String title, String desc,
                                               String initial, String suffix,
                                               java.util.function.IntConsumer onValue) {
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

        LinearLayout inputCol = new LinearLayout(ctx);
        inputCol.setOrientation(LinearLayout.HORIZONTAL);
        inputCol.setGravity(Gravity.CENTER_VERTICAL);
        EditText et = new EditText(ctx);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(initial);
        et.setTextSize(15);
        et.setGravity(Gravity.CENTER);
        et.setSingleLine(true);
        et.setTextColor(AppColors.text1());
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(AppColors.inputBg());
        etBg.setCornerRadius(8 * d);
        et.setBackground(etBg);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams((int)(64 * d), (int)(38 * d));
        inputCol.addView(et, etLp);
        if (suffix != null && !suffix.isEmpty()) {
            TextView sfx = new TextView(ctx);
            sfx.setText(suffix);
            sfx.setTextSize(14);
            sfx.setTextColor(AppColors.text2());
            sfx.setPadding((int)(6 * d), 0, 0, 0);
            inputCol.addView(sfx);
        }
        row.addView(inputCol);

        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String str = s == null ? "" : s.toString().trim();
                if (str.isEmpty()) return;
                try {
                    onValue.accept(Integer.parseInt(str));
                } catch (Throwable ignored) {}
            }
        });
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

    private static View spacerH(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams((int)(dpVal * d), 1));
        return v;
    }

    private static View speedRateRow(Context ctx, float d, float currentRate, FloatCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView label = new TextView(ctx);
        label.setText("语速调节");
        label.setTextSize(13);
        label.setTextColor(AppColors.text1());
        label.setTypeface(null, Typeface.BOLD);
        header.addView(label);

        TextView valueTv = new TextView(ctx);
        valueTv.setText(String.format("%.1fx", currentRate));
        valueTv.setTextSize(14);
        valueTv.setTextColor(AppColors.accent());
        valueTv.setTypeface(null, Typeface.BOLD);
        valueTv.setPadding((int)(12 * d), 0, 0, 0);
        header.addView(valueTv);

        row.addView(header);

        SeekBar sb = M3Page.slider(ctx);
        sb.setMax(20); // 0.5x ~ 2.5x, step 0.1
        sb.setProgress(Math.max(0, Math.min(20, Math.round((currentRate - 0.5f) * 10))));
        sb.setPadding(0, (int)(8 * d), 0, 0);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float rate = 0.5f + progress * 0.1f;
                valueTv.setText(String.format("%.1fx", rate));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                float rate = 0.5f + seekBar.getProgress() * 0.1f;
                if (cb != null) cb.onChange(rate);
            }
        });
        row.addView(sb);

        return row;
    }

    public interface TimeCallback { void onChange(String start, String end); }
    public interface IntCallback { void onChange(int value); }
    public interface StringCallback { void onChange(String value); }
    public interface FloatCallback { void onChange(float value); }

    // ===== TTS 引擎选择 (置顶卡片) =====

    private static View buildTtsEngineCard(Context ctx, Activity parentAct, float d, SharedPreferences prefs) {
        LinearLayout card = makeCard(ctx, d);

        TextView title = new TextView(ctx);
        title.setText("TTS 引擎选择");
        title.setTextSize(15);
        title.setTextColor(AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(8 * d));
        card.addView(title);

        // 单选按钮行
        LinearLayout radioRow = new LinearLayout(ctx);
        radioRow.setOrientation(LinearLayout.HORIZONTAL);
        radioRow.setPadding((int)(14 * d), (int)(4 * d), (int)(14 * d), (int)(8 * d));
        radioRow.setGravity(Gravity.CENTER_VERTICAL);

        String engine = WmPrefs.isTTSCube() ? "cube" : "system";
        boolean isCube = "cube".equals(engine);

        Button btnCube = new Button(ctx);
        btnCube.setText("配音魔方TTS");
        btnCube.setTextSize(13);
        btnCube.setAllCaps(false);
        btnCube.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable cubeBg = new android.graphics.drawable.GradientDrawable();
        cubeBg.setColor(isCube ? AppColors.accent() : AppColors.card());
        cubeBg.setCornerRadius((int)(6 * d));
        cubeBg.setStroke(isCube ? 0 : 1, AppColors.divider());
        btnCube.setBackground(cubeBg);
        btnCube.setTextColor(isCube ? AppColors.WHITE_TEXT : AppColors.text1());
        btnCube.setPadding((int)(18 * d), (int)(10 * d), (int)(18 * d), (int)(10 * d));
        LinearLayout.LayoutParams btnCubeLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnCubeLp.setMargins(0, 0, (int)(6 * d), 0);
        radioRow.addView(btnCube, btnCubeLp);

        Button btnSys = new Button(ctx);
        btnSys.setText("手机系统TTS");
        btnSys.setTextSize(13);
        btnSys.setAllCaps(false);
        btnSys.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable sysBg = new android.graphics.drawable.GradientDrawable();
        sysBg.setColor(!isCube ? AppColors.accent() : AppColors.card());
        sysBg.setCornerRadius((int)(6 * d));
        sysBg.setStroke(!isCube ? 0 : 1, AppColors.divider());
        btnSys.setBackground(sysBg);
        btnSys.setTextColor(!isCube ? AppColors.WHITE_TEXT : AppColors.text1());
        btnSys.setPadding((int)(18 * d), (int)(10 * d), (int)(18 * d), (int)(10 * d));
        LinearLayout.LayoutParams btnSysLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnSysLp.setMargins((int)(6 * d), 0, 0, 0);
        radioRow.addView(btnSys, btnSysLp);
        card.addView(radioRow);

        CandyUi.ripple(btnCube, AppColors.SHAPE_SM_DP);
        btnCube.setOnClickListener(v -> {
            WmPrefs.set("tts_cube", true);
            btnCube.setBackground(CandyUi.gradientBg(ctx, 6));
            btnCube.setTextColor(AppColors.WHITE_TEXT);
            android.graphics.drawable.GradientDrawable gd2 = new android.graphics.drawable.GradientDrawable();
            gd2.setColor(AppColors.card());
            gd2.setCornerRadius((int)(6 * d));
            gd2.setStroke(1, AppColors.divider());
            btnSys.setBackground(gd2);
            btnSys.setTextColor(AppColors.text1());
            Toast.makeText(parentAct, "已切换为配音魔方TTS", Toast.LENGTH_SHORT).show();
        });
        CandyUi.ripple(btnSys, AppColors.SHAPE_SM_DP);
        btnSys.setOnClickListener(v -> {
            WmPrefs.set("tts_cube", false);
            btnSys.setBackground(CandyUi.gradientBg(ctx, 6));
            btnSys.setTextColor(AppColors.WHITE_TEXT);
            android.graphics.drawable.GradientDrawable gd2 = new android.graphics.drawable.GradientDrawable();
            gd2.setColor(AppColors.card());
            gd2.setCornerRadius((int)(6 * d));
            gd2.setStroke(1, AppColors.divider());
            btnCube.setBackground(gd2);
            btnCube.setTextColor(AppColors.text1());
            Toast.makeText(parentAct, "已切换为手机系统TTS", Toast.LENGTH_SHORT).show();
        });

        // 配音魔方接口配置入口按钮
        Button cfgBtn = new Button(ctx);
        cfgBtn.setText("配音魔方接口配置");
        cfgBtn.setTextSize(14);
        cfgBtn.setAllCaps(false);
        cfgBtn.setTextColor(AppColors.WHITE_TEXT);
        cfgBtn.setGravity(Gravity.CENTER);
        cfgBtn.setBackground(CandyUi.gradientBg(ctx, 8));
        cfgBtn.setPadding(0, (int)(12 * d), 0, (int)(12 * d));
        LinearLayout.LayoutParams cfgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cfgLp.setMargins((int)(14 * d), (int)(8 * d), (int)(14 * d), (int)(12 * d));
        card.addView(cfgBtn, cfgLp);

        CandyUi.ripple(cfgBtn, AppColors.SHAPE_SM_DP);
        cfgBtn.setOnClickListener(v -> showTtsCubeDialog(ctx, parentAct, d));

        // v1086: 配音魔方接口配置下方 —— 「一键开启所有功能」总开关
        card.addView(itemDivider(ctx, d));
        card.addView(switchRow(ctx, d, "一键开启所有功能",
                "一键开启/关闭全部 TTS 转语音与自动播报功能开关",
                isAllFeaturesOn(prefs), (v, on) -> {
                    setAllFeatures(prefs, on);
                    try { SubPageActivity.refreshCurrent(parentAct); } catch (Throwable ignored) {}
                }));

        return card;
    }

    /** v1086: 汇总功能开关(不含时段/白名单等限制项) */
    private static final String[] ALL_FEATURE_ANNOUNCE_KEYS = new String[]{
            KEY_TTS_COMMAND,
            KEY_ANNOUNCE_TEXT, KEY_ANNOUNCE_IMAGE, KEY_ANNOUNCE_VIDEO, KEY_ANNOUNCE_LOCATION,
            KEY_ANNOUNCE_CARD, KEY_ANNOUNCE_FILE, KEY_ANNOUNCE_STICKER, KEY_ANNOUNCE_CALL,
            KEY_ANNOUNCE_QUOTE, KEY_ANNOUNCE_MINIPROGRAM, KEY_ANNOUNCE_VIDEOCHANNEL,
            KEY_ANNOUNCE_CHATHISTORY, KEY_ANNOUNCE_NICKNAME, KEY_ANNOUNCE_GROUP,
            KEY_ANNOUNCE_PAT, KEY_ANNOUNCE_AT, KEY_TEXT_TRUNCATE
    };

    /** v1086: 所有功能开关是否全部开启 */
    private static boolean isAllFeaturesOn(SharedPreferences prefs) {
        if (prefs == null) return false;
        for (String k : ALL_FEATURE_ANNOUNCE_KEYS) {
            if (!prefs.getBoolean(k, false)) return false;
        }
        return WmPrefs.get("auto_voice", false)
                && WmPrefs.get("voice_enhance", false)
                && WmPrefs.get("ls_tts_false_dur_on", false);
    }

    /** v1086: 一键设置全部功能开关 */
    private static void setAllFeatures(SharedPreferences prefs, boolean on) {
        if (prefs != null) {
            SharedPreferences.Editor e = prefs.edit();
            for (String k : ALL_FEATURE_ANNOUNCE_KEYS) e.putBoolean(k, on);
            e.apply();
        }
        WmPrefs.set("auto_voice", on);
        WmPrefs.set("voice_enhance", on);
        WmPrefs.set("ls_tts_false_dur_on", on);
    }

    // ===== 配音魔方接口配置对话框 (peiyinmofang.com) =====

    private static final String PMF_BASE = "https://peiyinmofang.com";

    public static void showTtsCubeDialog(Context ctx, Activity parentAct, float d) {
        if (sTtsCubeDialog != null && sTtsCubeDialog.isShowing()) {
            sTtsCubeDialog.dismiss();
        }
        String savedKey = WmPrefs.getStr("tts_cube_key", "");

        TextView statusTv = new TextView(ctx);
        statusTv.setText("加载中...");
        statusTv.setTextSize(13);
        statusTv.setTextColor(AppColors.text2());
        statusTv.setPadding((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(6 * d));

        // 外层根布局：与主页一致的渐变背景（标题栏 + 可滚动内容 + 固定底部）
        LinearLayout outerLayout = new LinearLayout(ctx);
        outerLayout.setOrientation(LinearLayout.VERTICAL);
        outerLayout.setBackground(CandyUi.pageGradient());
        outerLayout.setPadding((int)(12 * d), (int)(12 * d), (int)(12 * d), (int)(12 * d));

        // 标题栏卡片
        LinearLayout titleBar = new LinearLayout(ctx);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleBar.setPadding((int)(16 * d), (int)(12 * d), (int)(12 * d), (int)(12 * d));
        titleBar.setBackground(CandyUi.cardBg(ctx));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleBar.setLayoutParams(titleLp);

        TextView titleTv = new TextView(ctx);
        titleTv.setText("配音魔方接口配置");
        titleTv.setTextSize(16);
        titleTv.setTextColor(AppColors.TEXT_TITLE);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleBar.addView(titleTv, ttlp);

        Button keyBtn = new Button(ctx);
        keyBtn.setText("");
        keyBtn.setMinimumWidth(0);
        keyBtn.setMinimumHeight(0);
        keyBtn.setPadding(0, 0, 0, 0);
        keyBtn.setBackground(new TuneIconDrawable(AppColors.accent()));
        int hexSize = (int)(36 * d);
        titleBar.addView(keyBtn, new LinearLayout.LayoutParams(hexSize, hexSize));

        outerLayout.addView(titleBar);
        outerLayout.addView(candyDivider(ctx, d));

        // 音色库标题
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tabRow.setPadding((int)(12 * d), (int)(4 * d), (int)(12 * d), (int)(4 * d));

        TextView tabCube = new TextView(ctx);
        tabCube.setText("配音魔方音色库");
        tabCube.setTextSize(13);
        tabCube.setGravity(android.view.Gravity.CENTER);
        tabCube.setPadding((int)(12 * d), (int)(8 * d), (int)(12 * d), (int)(8 * d));
        tabCube.setBackground(CandyUi.gradientBg(ctx, 16));
        tabCube.setTextColor(AppColors.WHITE_TEXT);
        LinearLayout.LayoutParams tabL2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tabL2.setMargins((int)(6 * d), 0, 0, 0);
        tabRow.addView(tabCube, tabL2);
        outerLayout.addView(tabRow);

        // 内容区卡片（可滚动，weight=1）
        LinearLayout contentCard = new LinearLayout(ctx);
        contentCard.setOrientation(LinearLayout.VERTICAL);
        contentCard.setBackground(CandyUi.cardBg(ctx));
        contentCard.setPadding((int)(12 * d), (int)(12 * d), (int)(12 * d), (int)(12 * d));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        contentCard.setLayoutParams(cardLp);

        // 音色列表容器 (动态填充)
        LinearLayout voiceList = new LinearLayout(ctx);
        voiceList.setOrientation(LinearLayout.VERTICAL);
        contentCard.addView(voiceList);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(contentCard);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(svLp);
        outerLayout.addView(sv);

        // 底部固定操作栏（不跟随滑动）
        LinearLayout bottomBar = new LinearLayout(ctx);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bottomBar.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), 0);

        Button saveBtn = new Button(ctx);
        saveBtn.setText("保存");
        saveBtn.setTextSize(14);
        saveBtn.setAllCaps(false);
        saveBtn.setTextColor(AppColors.WHITE_TEXT);
        saveBtn.setBackground(CandyUi.gradientBg(ctx, 8));
        saveBtn.setPadding(0, (int)(10 * d), 0, (int)(10 * d));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        saveLp.setMargins(0, 0, (int)(8 * d), 0);
        bottomBar.addView(saveBtn, saveLp);

        Button closeBtn = new Button(ctx);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(14);
        closeBtn.setAllCaps(false);
        closeBtn.setTextColor(AppColors.text1());
        android.graphics.drawable.GradientDrawable closeBg = new android.graphics.drawable.GradientDrawable();
        closeBg.setColor(AppColors.card());
        closeBg.setCornerRadius((int)(8 * d));
        closeBg.setStroke(1, AppColors.divider());
        closeBtn.setBackground(closeBg);
        closeBtn.setPadding(0, (int)(10 * d), 0, (int)(10 * d));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bottomBar.addView(closeBtn, closeLp);

        outerLayout.addView(candyDivider(ctx, d));
        outerLayout.addView(bottomBar);

        AlertDialog dialog = new AlertDialog.Builder(parentAct)
                .setView(outerLayout)
                .create();
        sTtsCubeDialog = dialog;
        dialog.show();

        // 调整窗口大小：宽度 85%（+5），高度 75%（+5）
        try {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                android.util.DisplayMetrics dm = parentAct.getResources().getDisplayMetrics();
                window.setLayout((int) (dm.widthPixels * 0.85f), (int) (dm.heightPixels * 0.75f));
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(AppColors.CARD_BG));
            }
        } catch (Throwable ignored) {}

        CandyUi.ripple(keyBtn, AppColors.SHAPE_FULL_DP);
        keyBtn.setOnClickListener(v -> showKeyInputPopup(ctx, parentAct, d, keyBtn, voiceList, statusTv));

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        CandyUi.ripple(closeBtn, AppColors.SHAPE_SM_DP);
        saveBtn.setOnClickListener(v -> dialog.dismiss());
        CandyUi.ripple(saveBtn, AppColors.SHAPE_SM_DP);

        // 加载配音魔方音色库
        CandyUi.ripple(tabCube, AppColors.SHAPE_FULL_DP);
        tabCube.setOnClickListener(v -> {
            String k = WmPrefs.getStr("tts_cube_key", "");
            if (k.isEmpty()) {
                TextView hintTv = new TextView(ctx);
                hintTv.setText("点击右上角六角图标设置 Key 后加载音色");
                hintTv.setTextSize(13);
                hintTv.setTextColor(AppColors.text2());
                hintTv.setPadding((int)(12 * d), (int)(12 * d), (int)(12 * d), 0);
                voiceList.removeAllViews();
                voiceList.addView(hintTv);
            } else {
                loadVoices(ctx, parentAct, d, voiceList, statusTv, k);
            }
        });

        // 自动加载：已有 Key 时直接加载音色，无需点击六角图标
        if (savedKey.isEmpty()) {
            TextView hintTv = new TextView(ctx);
            hintTv.setText("点击右上角六角图标设置 Key 后加载音色");
            hintTv.setTextSize(13);
            hintTv.setTextColor(AppColors.text2());
            hintTv.setPadding((int)(12 * d), (int)(12 * d), (int)(12 * d), 0);
            voiceList.addView(hintTv);
        } else {
            loadVoices(ctx, parentAct, d, voiceList, statusTv, savedKey);
        }
    }

    /** 加载配音魔方音色列表到指定容器，public 以便跨类调用 */
    public static void loadVoices(Context ctx, Activity parentAct, float d,
                                   LinearLayout voiceList, TextView statusTv, String key) {
        voiceList.removeAllViews();
        voiceList.addView(statusTv);
        statusTv.setText("校验Key中...");
        new Thread(() -> {
            String checkResult = checkTtsKey(key);
            parentAct.runOnUiThread(() -> {
                statusTv.setText(checkResult);
                if (!checkResult.startsWith("有效")) return;
                statusTv.setText("拉取内置音色列表...");
            });
            java.util.List<VoiceItem> builtin = fetchBuiltinVoices(key);
            parentAct.runOnUiThread(() -> statusTv.setText("拉取自定义音色列表..."));
            java.util.List<VoiceItem> custom = fetchUserVoices(key);
            parentAct.runOnUiThread(() -> {
                voiceList.removeView(statusTv);
                buildVoiceListUI(ctx, parentAct, d, voiceList, key, builtin, custom);
            });
        }).start();
    }

    private static void showKeyInputPopup(Context ctx, Activity parentAct, float d, Button keyBtn,
                                          LinearLayout voiceList, TextView statusTv) {
        LinearLayout root = M3Page.root(ctx);
        root.addView(M3Page.section(ctx, "设置 API Key", "配置配音魔方接口密钥"));

        LinearLayout card = M3Page.card(ctx);
        card.addView(M3Page.fieldLabel(ctx, "API Key"));
        final EditText keyEt = M3Page.input(ctx, "输入 API Key");
        keyEt.setText(WmPrefs.getStr("tts_cube_key", ""));
        M3Page.trimEdgesOnInput(keyEt);
        card.addView(keyEt);
        root.addView(card);

        AlertDialog.Builder b = new AlertDialog.Builder(parentAct);
        b.setView(InsetsUtil.window(null, root, 0.9f, 0.5f));
        b.setCancelable(true);
        AlertDialog dlg = b.create();
        InsetsUtil.center(dlg, 0.9f, 0.5f);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            InsetsUtil.transparentWindow(w);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        View saveBtn = M3Page.button(ctx, "保存", () -> {
            String key = keyEt.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(parentAct, "请输入Key", Toast.LENGTH_SHORT).show();
                return;
            }
            WmPrefs.setStr("tts_cube_key", key);
            Toast.makeText(parentAct, "Key已保存", Toast.LENGTH_SHORT).show();
            loadVoices(ctx, parentAct, d, voiceList, statusTv, key);
            dlg.dismiss();
        });
        View cancelBtn = M3Page.ghostButton(ctx, "取消", dlg::dismiss);
        root.addView(M3Page.buttonRow(ctx, saveBtn, cancelBtn));

        InsetsUtil.clearDialogShell(dlg);
        dlg.show();
        InsetsUtil.clearDialogShell(dlg);
    }

    // ===== 音色列表UI构建 =====

    private static void buildVoiceListUI(Context ctx, Activity parentAct, float d,
                                          LinearLayout voiceList, String key,
                                          java.util.List<VoiceItem> builtin, java.util.List<VoiceItem> custom) {
        buildVoiceListUI(ctx, parentAct, d, voiceList, key, builtin, custom, "");
    }

    private static void buildVoiceListUI(Context ctx, Activity parentAct, float d,
                                          LinearLayout voiceList, String key,
                                          java.util.List<VoiceItem> builtin, java.util.List<VoiceItem> custom,
                                          String searchHint) {
        String savedVoice = WmPrefs.getStr("tts_cube_voice", "");

        // 标题栏
        TextView listTitle = new TextView(ctx);
        listTitle.setText("选择默认音色");
        listTitle.setTextSize(14);
        listTitle.setTextColor(AppColors.TEXT_TITLE);
        listTitle.setTypeface(null, Typeface.BOLD);
        listTitle.setPadding(0, (int)(8 * d), 0, (int)(8 * d));
        voiceList.addView(listTitle);

        // 搜索框
        EditText searchEt = new EditText(ctx);
        searchEt.setHint("搜索音色名称/影视剧...");
        searchEt.setTextSize(13);
        searchEt.setSingleLine(true);
        searchEt.setPadding((int)(8 * d), (int)(8 * d), (int)(8 * d), (int)(8 * d));
        searchEt.setHintTextColor(AppColors.text3());
        if (searchHint != null && !searchHint.isEmpty()) {
            searchEt.setText(searchHint);
        }
        android.graphics.drawable.GradientDrawable sBg = new android.graphics.drawable.GradientDrawable();
        sBg.setColor(AppColors.inputBg());
        sBg.setCornerRadius((int)(6 * d));
        sBg.setStroke((int)(1.5f * d), AppColors.candyPink());
        searchEt.setBackground(sBg);
        voiceList.addView(searchEt);


        // 刷新回调：重新构建列表并保留搜索关键词
        final Runnable[] refreshUi = new Runnable[1];
        refreshUi[0] = () -> {
            String q = searchEt.getText().toString();
            voiceList.removeAllViews();
            buildVoiceListUI(ctx, parentAct, d, voiceList, key, builtin, custom, q);
        };

        // 收集所有voice rows用于搜索
        final java.util.List<View> allVoiceRows = new java.util.ArrayList<>();
        final java.util.List<String> allVoiceSearchText = new java.util.ArrayList<>();
        final java.util.List<View> groupHeaders = new java.util.ArrayList<>();

        // 内置音色 (按影视剧分组)
        if (builtin != null && !builtin.isEmpty()) {
            TextView secTitle = new TextView(ctx);
            secTitle.setText("内置音色 (按影视剧分组):");
            secTitle.setTextSize(13);
            secTitle.setTextColor(AppColors.text1());
            secTitle.setTypeface(null, Typeface.BOLD);
            secTitle.setPadding(0, (int)(12 * d), 0, (int)(6 * d));
            voiceList.addView(secTitle);

            // 按 group 分组
            String currentGroup = null;
            for (VoiceItem vi : builtin) {
                if (!vi.group.equals(currentGroup)) {
                    currentGroup = vi.group;
                    TextView gTitle = new TextView(ctx);
                    gTitle.setText("  " + vi.group);
                    gTitle.setTextSize(13);
                    gTitle.setTextColor(AppColors.accent());
                    gTitle.setTypeface(null, Typeface.BOLD);
                    gTitle.setPadding(0, (int)(8 * d), 0, (int)(4 * d));
                    voiceList.addView(gTitle);
                    groupHeaders.add(gTitle);
                }
                View row = buildVoiceRow(ctx, parentAct, d, key, vi, savedVoice, refreshUi[0]);
                voiceList.addView(row);
                allVoiceRows.add(row);
                allVoiceSearchText.add((vi.displayName != null ? vi.displayName : vi.voiceId) + "|" + vi.group + "|" + (vi.actor != null ? vi.actor : ""));
            }
        }

        // 用户自定义音色
        if (custom != null && !custom.isEmpty()) {
            TextView secTitle = new TextView(ctx);
            secTitle.setText("我的自定义音色:");
            secTitle.setTextSize(13);
            secTitle.setTextColor(AppColors.text1());
            secTitle.setTypeface(null, Typeface.BOLD);
            secTitle.setPadding(0, (int)(12 * d), 0, (int)(6 * d));
            voiceList.addView(secTitle);
            for (VoiceItem vi : custom) {
                View row = buildVoiceRow(ctx, parentAct, d, key, vi, savedVoice, refreshUi[0]);
                voiceList.addView(row);
                allVoiceRows.add(row);
                allVoiceSearchText.add((vi.displayName != null ? vi.displayName : vi.voiceId));
            }
        }

        if (allVoiceRows.isEmpty()) {
            TextView emptyTv = new TextView(ctx);
            emptyTv.setText("未找到可用音色");
            emptyTv.setTextSize(13);
            emptyTv.setTextColor(AppColors.text2());
            emptyTv.setPadding(0, (int)(12 * d), 0, 0);
            voiceList.addView(emptyTv);
        }

        // 搜索过滤
        searchEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int cnt, int aft) {}
            @Override public void onTextChanged(CharSequence s, int st, int bef, int cnt) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String q = s.toString().trim().toLowerCase();
                for (int i = 0; i < allVoiceRows.size(); i++) {
                    boolean match = q.isEmpty() || allVoiceSearchText.get(i).toLowerCase().contains(q);
                    allVoiceRows.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                }
                // 隐藏/显示分组标题
                for (View gh : groupHeaders) {
                    int idx = voiceList.indexOfChild(gh);
                    boolean hasVisible = false;
                    for (int j = idx + 1; j < voiceList.getChildCount(); j++) {
                        View child = voiceList.getChildAt(j);
                        if (groupHeaders.contains(child)) break;
                        if (child.getVisibility() == View.VISIBLE) { hasVisible = true; break; }
                    }
                    gh.setVisibility(hasVisible ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    private static View buildVoiceRow(Context ctx, Activity parentAct, float d,
                                        String key, VoiceItem vi, String savedVoice, Runnable onSelected) {
        LinearLayout vRow = new LinearLayout(ctx);
        vRow.setOrientation(LinearLayout.HORIZONTAL);
        vRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        vRow.setPadding(0, (int)(4 * d), 0, (int)(4 * d));

        boolean isSelected = vi.voiceId.equals(savedVoice);

        // 选择按钮（左侧）
        TextView selBtn = new TextView(ctx);
        selBtn.setText(isSelected ? "\u2714" : "");
        selBtn.setTextSize(13);
        selBtn.setGravity(android.view.Gravity.CENTER);
        selBtn.setTextColor(isSelected ? AppColors.accent() : AppColors.text3());
        selBtn.setTypeface(null, Typeface.BOLD);
        android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
        selBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        selBg.setColor(isSelected ? 0x332196F3 : 0x00FFFFFF);
        selBg.setStroke((int)(1.5f * d), isSelected ? AppColors.accent() : AppColors.text3());
        selBtn.setBackground(selBg);
        int selSize = (int)(22 * d);
        selBtn.setLayoutParams(new LinearLayout.LayoutParams(selSize, selSize));
        vRow.addView(selBtn);

        vRow.addView(spacerH(ctx, d, 8));

        // 名称 + 演员（流体渐变霓虹糖果色 + 加粗）
        String displayName = vi.displayName != null ? vi.displayName : vi.voiceId;
        String subtitle = (vi.actor != null && !vi.actor.isEmpty()) ? " (" + vi.actor + ")" : "";
        TextView vName = new TextView(ctx);
        vName.setText(displayName + subtitle);
        vName.setTextSize(13);
        vName.setTextColor(AppColors.text1());
        vName.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams vnlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vRow.addView(vName, vnlp);

        // 试听按钮
        TextView listenBtn = new TextView(ctx);
        listenBtn.setText("试听");
        listenBtn.setTextSize(12);
        listenBtn.setTextColor(AppColors.accent());
        listenBtn.setGravity(Gravity.CENTER);
        listenBtn.setPadding((int)(8 * d), (int)(4 * d), (int)(8 * d), (int)(4 * d));
        listenBtn.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        vRow.addView(listenBtn);

        CandyUi.ripple(listenBtn, AppColors.SHAPE_FULL_DP);
        listenBtn.setOnClickListener(v3 -> {
            new Thread(() -> {
                String result = ttsPreviewVoice(key, vi.voiceId, "欢迎使用配音魔方");
                if (result.startsWith("OK:")) {
                    MediaPlayer mp = new MediaPlayer();
                    try {
                        mp.setDataSource(result.substring(3));
                        mp.prepare();
                        mp.start();
                        mp.setOnCompletionListener(MediaPlayer::release);
                        parentAct.runOnUiThread(() ->
                                Toast.makeText(parentAct, "试听: " + displayName, Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        parentAct.runOnUiThread(() ->
                                Toast.makeText(parentAct, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }
                } else {
                    parentAct.runOnUiThread(() ->
                            Toast.makeText(parentAct, result, Toast.LENGTH_SHORT).show());
                }
            }).start();
        });

        CandyUi.ripple(selBtn, AppColors.SHAPE_FULL_DP);
        selBtn.setOnClickListener(v3 -> {
            WmPrefs.setStr("tts_cube_voice", vi.voiceId);
            Toast.makeText(parentAct, "已选择默认音色: " + displayName, Toast.LENGTH_SHORT).show();
            if (onSelected != null) onSelected.run();
        });

        return vRow;
    }

    // ===== 音色数据模型 =====

    public static class VoiceItem {
        public String voiceId;
        public String group;       // 影视剧名
        public String displayName;  // 角色名
        public String actor;        // 演员
        public VoiceItem(String voiceId, String group, String displayName, String actor) {
            this.voiceId = voiceId;
            this.group = group;
            this.displayName = displayName;
            this.actor = actor;
        }
    }

    /** v985: 供 AI 助手会话/模板配置复用: 拉取配音魔方全部音色(内置 + 我的)。 */
    public static java.util.List<VoiceItem> fetchAllVoices(String key) {
        java.util.List<VoiceItem> all = new java.util.ArrayList<>();
        if (key == null || key.trim().isEmpty()) return all;
        all.addAll(fetchBuiltinVoices(key));
        all.addAll(fetchUserVoices(key));
        return all;
    }

    // ===== API 调用 =====

    private static String checkTtsKey(String key) {
        try {
             java.net.URL url = new java.net.URL(PMF_BASE + "/api/open/v1/me");
             java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
             try {
             conn.setRequestMethod("GET");
             conn.setRequestProperty("Authorization", "Bearer " + key);
             conn.setConnectTimeout(8000);
             conn.setReadTimeout(8000);
             int code = conn.getResponseCode();
             if (code == 200) {
                 java.io.InputStream is = conn.getInputStream();
                 try {
                 String body = readAllAsString(is);
                 try {
                     JSONObject jo = safeJsonObject(body);
                     int status = jo.optInt("status", -1);
                     if (status == 200) return "有效: " + jo.optString("message", "OK");
                 } catch (Exception ignored) {}
                 return "Key 有效";
                 } finally { is.close(); }
             }
             String err = readErrorStream(conn);
             if (code == 401) return "无效: 认证失败(401)" + (err.isEmpty() ? "" : " - " + err);
             if (code == 403) return "无效: Key被禁用或余额不足(403)" + (err.isEmpty() ? "" : " - " + err);
             return "检测失败: HTTP " + code + (err.isEmpty() ? "" : " - " + err);
             } finally { conn.disconnect(); }
        } catch (Exception e) {
            return "检测失败: " + e.getMessage();
        }
    }

    private static java.util.List<VoiceItem> fetchBuiltinVoices(String key) {
        java.util.List<VoiceItem> list = new java.util.ArrayList<>();
        try {
             java.net.URL url = new java.net.URL(PMF_BASE + "/api/open/v1/voices");
             java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
             try {
             conn.setRequestMethod("GET");
             conn.setRequestProperty("Authorization", "Bearer " + key);
             conn.setConnectTimeout(10000);
             conn.setReadTimeout(10000);
             int code = conn.getResponseCode();
             if (code != 200) return list;
             java.io.InputStream is = conn.getInputStream();
             try {
             String body = readAllAsString(is);
             JSONObject jo = safeJsonObject(body);
             if (jo.optInt("status") != 200) return list;
             JSONArray data = jo.optJSONArray("data");
             if (data == null) return list;
             for (int i = 0; i < data.length(); i++) {
                 JSONObject drama = data.getJSONObject(i);
                String title = drama.optString("title", "未知剧集");
                JSONArray chars = drama.optJSONArray("characters");
                if (chars == null) continue;
                for (int j = 0; j < chars.length(); j++) {
                    JSONObject chr = chars.getJSONObject(j);
                    String voiceId = chr.optString("voice_id", "");
                    if (voiceId.isEmpty()) continue; // 跳过无 voice_id 的条目
                    String name = chr.optString("name", voiceId);
                    String actor = chr.optString("actor", "");
                    list.add(new VoiceItem(voiceId, title, name, actor));
                }
             }
                    } finally { is.close(); }
             } finally { conn.disconnect(); }
         } catch (Exception e) {
             // 静默失败
         }
         return list;
     }

     private static java.util.List<VoiceItem> fetchUserVoices(String key) {
        java.util.List<VoiceItem> list = new java.util.ArrayList<>();
        try {
             java.net.URL url = new java.net.URL(PMF_BASE + "/api/open/v1/user-voices");
             java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
             try {
             conn.setRequestMethod("GET");
             conn.setRequestProperty("Authorization", "Bearer " + key);
             conn.setConnectTimeout(10000);
             conn.setReadTimeout(10000);
             int code = conn.getResponseCode();
             if (code != 200) return list;
             java.io.InputStream is = conn.getInputStream();
             try {
             String body = readAllAsString(is);
             JSONObject jo = safeJsonObject(body);
             if (jo.optInt("status") != 200) return list;
             JSONArray data = jo.optJSONArray("data");
             if (data == null) return list;
             for (int i = 0; i < data.length(); i++) {
                 JSONObject uv = data.getJSONObject(i);
                 String voiceId = uv.optString("voice_id", "");
                 if (voiceId.isEmpty()) continue;
                 String name = uv.optString("name", voiceId);
                 list.add(new VoiceItem(voiceId, "我的音色", name, ""));
             }
                    } finally { is.close(); }
             } finally { conn.disconnect(); }
        } catch (Exception e) {
            // 静默失败
        }
        return list;
    }

    /** 配音魔方下载合成音频到缓存 WAV，返回 "OK:绝对路径" 或错误文本 */
    public static String ttsPreviewVoice(String key, String voiceId, String text) {
        try {
            JSONObject req = new JSONObject();
            req.put("voiceId", voiceId);
            req.put("text", text);
            String body = req.toString();

             java.net.URL url = new java.net.URL(PMF_BASE + "/api/open/v1/tts/simple-generate");
             java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
             try {
             conn.setRequestMethod("POST");
             conn.setRequestProperty("X-API-Key", key);
             conn.setRequestProperty("Content-Type", "application/json");
             conn.setDoOutput(true);
             conn.setConnectTimeout(15000);
             conn.setReadTimeout(15000);
             java.io.OutputStream os = conn.getOutputStream();
             try {
             os.write(body.getBytes("UTF-8"));
             os.flush();
             } finally { os.close(); }

             int code = conn.getResponseCode();
             if (code != 200) {
                 String err = readErrorStream(conn);
                 return "合成失败: HTTP " + code + (err.isEmpty() ? "" : " - " + err);
             }

             java.io.InputStream is = conn.getInputStream();
             try {
             String respStr = readAllAsString(is);

             JSONObject jo = safeJsonObject(respStr);
             if (jo.optInt("status") != 200) {
                 return "合成失败: " + jo.optString("message", "状态非200");
             }
             JSONObject data = jo.optJSONObject("data");
             if (data == null) return "合成失败: 响应无data字段";
             String audioUrl = data.optString("audio");
             if (audioUrl == null || audioUrl.isEmpty()) return "合成失败: 响应无音频URL";

             // 下载音频文件
             java.net.URL audioURL = new java.net.URL(audioUrl);
             java.net.HttpURLConnection audioConn = (java.net.HttpURLConnection) audioURL.openConnection();
             try {
             audioConn.setConnectTimeout(15000);
             audioConn.setReadTimeout(15000);
             int audioCode = audioConn.getResponseCode();
             if (audioCode != 200) {
                 return "下载音频失败: HTTP " + audioCode;
             }

             String safeName = voiceId.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
             Context appCtx = com.leshao.v3.ContextManager.getAppContext();
             File cacheDir = appCtx != null ? appCtx.getCacheDir() : null;
             if (cacheDir == null) cacheDir = new File(Environment.getExternalStorageDirectory(), "leshao_v3_cache");
             File ttsDir = new File(cacheDir, "tts_preview");
             ttsDir.mkdirs();
             File outFile = new File(ttsDir, safeName + ".wav");

             java.io.InputStream audioIs = audioConn.getInputStream();
             java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
             try {
             byte[] buf = new byte[8192];
             int n;
             while ((n = audioIs.read(buf)) > 0) fos.write(buf, 0, n);
             fos.flush();
             } finally {
             try { fos.close(); } catch (Exception ignored) {}
             try { audioIs.close(); } catch (Exception ignored) {}
             }

             // 验证文件大小
             if (outFile.length() < 100) {
                 return "下载失败: 音频文件过小(" + outFile.length() + "字节)";
             }
             return "OK:" + outFile.getAbsolutePath();
             } finally { audioConn.disconnect(); }
             } finally { is.close(); }
             } finally { conn.disconnect(); }
        } catch (Exception e) {
            return "合成失败: " + e.getMessage();
        }
    }

    /** 读 InputStream 为 String (UTF-8) */
    private static String readAllAsString(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 读 HTTP 错误响应体 */
    private static String readErrorStream(java.net.HttpURLConnection conn) {
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return "";
            String body = readAllAsString(es);
            es.close();
            if (body.length() > 200) body = body.substring(0, 200) + "...";
            return body;
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject safeJsonObject(String raw) throws Exception {
        if (raw == null || raw.isEmpty()) throw new Exception("响应为空");
        char first = raw.trim().charAt(0);
        if (first != '{' && first != '[') throw new Exception("接口返回非JSON数据");
        return new JSONObject(raw);
    }

    private static View candyDivider(Context ctx, float d) {
        return M3Page.divider(ctx);
    }

    /** 设置图标: Material 3「推子/滑块」(tune) 空心描边样式, 逻辑坐标系 24x24。 */
    private static final class TuneIconDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint;
        private final android.graphics.Path path = new android.graphics.Path();

        TuneIconDrawable(int color) {
            paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setColor(color);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float size = Math.min(b.width(), b.height());
            float strokeW = Math.max(1.5f, size * 0.09f);
            paint.setStrokeWidth(strokeW);
            float u = (size - strokeW * 2f) / 24f;
            float ox = b.exactCenterX() - 12f * u;
            float oy = b.exactCenterY() - 12f * u;
            path.reset();

            // 三行推子(逻辑 y = 7 / 12 / 17), 圆环滑块
            float[] ys = {7f, 12f, 17f};
            float[] knobX = {16f, 11f, 13f};
            float[] leftEnd = {13f, 8f, 10f};
            float[] rightStart = {-1f, 15f, 17f};
            float knobR = 2.2f;
            for (int i = 0; i < 3; i++) {
                float y = oy + ys[i] * u;
                path.moveTo(ox + 4f * u, y);
                path.lineTo(ox + leftEnd[i] * u, y);
                path.addCircle(ox + knobX[i] * u, y, knobR * u,
                        android.graphics.Path.Direction.CW);
                if (rightStart[i] > 0) {
                    path.moveTo(ox + rightStart[i] * u, y);
                    path.lineTo(ox + 20f * u, y);
                }
            }
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
