package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.ScheduleBroadcast;
import com.leshao.v3.hook.ScheduleBroadcast.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ScheduleMsgPageView {

    private static final int CLR_BG = 0xFF0A1814;
    private static final int CLR_CARD = 0xFF142420;
    private static final int CLR_NEON = 0xFF39FF88;
    private static final int CLR_WHITE = 0xFFFFFFFF;
    private static final int CLR_HIGHLIGHT = 0xFF6DFFAA;
    private static final int CLR_YELLOW = 0xFFFFD02E;
    private static final int CLR_GRAY = 0xFF888888;
    private static final int CLR_DARK = 0xFF333333;
    private static final int CLR_RED = 0xFFFF5252;

    private static final String[] MSG_TYPES = {"文本消息","图片消息","视频消息","图文消息","收藏消息","语音消息","位置消息","程序消息","艾特公告","名片消息","文件消息","XML消息"};
    private static final int[] MSG_TYPE_CODES = {1, 3, 43, 49, 100, 34, 48, 1001, 1, 42, 6, 49};
    private static final String[] REPEAT_MODES = {"仅一次", "每日循环", "每周循环", "间隔N天循环"};
    private static final String[] CHANNELS = {"转发好友", "转发群聊", "发布朋友圈"};

    private static int sSelectedMsgType = 0;
    private static int sSelectedChannel = 1;
    private static Set<String> sSelectedContacts = new LinkedHashSet<>();
    private static Set<String> sExcludeContacts = new LinkedHashSet<>();
    private static List<String> sMaterialFiles = new ArrayList<>();
    private static String sEditingTaskId = null;
    private static String sTaskNameCache = "";
    private static String sContentCache = "";
    private static int sHourCache = 8;
    private static int sMinuteCache = 0;
    private static String sRepeatCache = "仅一次";

    private static View sProgressBar;
    private static View sProgressFill;
    private static TextView sProgressText;
    private static TextView sProgressCount;
    private static TextView sEmergencyBtn;

    private static ContactPickerDialog.OnContactsSelected sLastContactsCallback;
    private static Activity sParentActivity;

    // ===== 主入口 =====

    public static View create(Context ctx, Activity parentAct) {
        try {
            sParentActivity = parentAct;
            float d = dp(ctx);
            LogWriter.log("SCHEDULE_MSG", "create: START, d=" + d);

            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setBackgroundColor(CLR_BG);
            body.setPadding(PX(d, 10), PX(d, 10), PX(d, 10), PX(d, 16));

            LogWriter.log("SCHEDULE_MSG", "create: buildProgressBar...");
            body.addView(buildProgressBar(ctx, d));
            LogWriter.log("SCHEDULE_MSG", "create: buildCard1...");
            body.addView(vSpacer(ctx, d, 10));
            body.addView(buildCard1(ctx, d, parentAct));

            loadDraft(ctx);

            LogWriter.log("SCHEDULE_MSG", "create: DONE OK");
            return body;
        } catch (Throwable t) {
            LogWriter.log("SCHEDULE_MSG", "create: CRASH: " + t.getClass().getName() + ": " + t.getMessage());
            throw new RuntimeException(t);
        }
    }

    // ===== 进度状态栏 =====

    private static View buildProgressBar(Context ctx, float d) {
        // 极简占位
        View bar = new View(ctx);
        bar.setBackgroundColor(CLR_NEON);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, PX(d, 36)));
        return bar;
    }

    // 原始 buildProgressBar 保留备查
    private static View _buildProgressBar(Context ctx, float d) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(PX(d, 6), PX(d, 8), PX(d, 6), PX(d, 8));
        bar.setClipToOutline(true);

        int barW = ctx.getResources().getDisplayMetrics().widthPixels - PX(d, 20);
        int barH = PX(d, 36);

        sProgressBar = new View(ctx) {
            @Override
            protected void onDraw(Canvas canvas) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(CLR_DARK);
                Path path = buildChamferPath(new RectF(0, 0, getWidth(), getHeight()), PX(d, 8));
                canvas.drawPath(path, p);
            }
        };
        sProgressBar.setLayoutParams(new LinearLayout.LayoutParams(barW, barH));
        bar.addView(sProgressBar);

        LinearLayout overlay = new LinearLayout(ctx);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(PX(d, 14), 0, PX(d, 10), 0);
        overlay.setLayoutParams(new LinearLayout.LayoutParams(barW, barH));

        sProgressFill = new View(ctx);
        sProgressFill.setBackgroundColor(CLR_NEON);
        sProgressFill.setLayoutParams(new LinearLayout.LayoutParams(0, PX(d, 6), 0.0f));
        overlay.addView(sProgressFill);

        View sp1 = new View(ctx); sp1.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 8), 0)); overlay.addView(sp1);

        sProgressText = new TextView(ctx);
        sProgressText.setText("空闲");
        sProgressText.setTextSize(10);
        sProgressText.setTextColor(CLR_GRAY);
        sProgressText.setTypeface(null, Typeface.BOLD);
        overlay.addView(sProgressText);

        View sp2 = new View(ctx); sp2.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1.0f)); overlay.addView(sp2);

        sProgressCount = new TextView(ctx);
        sProgressCount.setText("第0/0");
        sProgressCount.setTextSize(10);
        sProgressCount.setTextColor(CLR_GRAY);
        sProgressCount.setPadding(0, 0, PX(d, 8), 0);
        overlay.addView(sProgressCount);

        sEmergencyBtn = new TextView(ctx);
        sEmergencyBtn.setText("停止");
        sEmergencyBtn.setTextSize(9);
        sEmergencyBtn.setTextColor(CLR_WHITE);
        sEmergencyBtn.setTypeface(null, Typeface.BOLD);
        sEmergencyBtn.setPadding(PX(d, 10), PX(d, 4), PX(d, 10), PX(d, 4));
        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setCornerRadius(PX(d, 4));
        stopBg.setColor(CLR_RED);
        sEmergencyBtn.setBackground(stopBg);
        sEmergencyBtn.setVisibility(View.GONE);
        sEmergencyBtn.setOnClickListener(v -> {
            ScheduleBroadcast.emergencyStop();
            sEmergencyBtn.setVisibility(View.GONE);
            updateProgressState(0, 0, false);
        });
        overlay.addView(sEmergencyBtn);

        ((ViewGroup)bar.getParent() != null ? bar : bar).post(() -> {
            FrameLayout f = new FrameLayout(ctx);
            f.setLayoutParams(new LinearLayout.LayoutParams(barW, barH));
            f.addView(sProgressBar);
            f.addView(overlay);
            LinearLayout par = (LinearLayout) bar.getParent();
            if (par != null) {
                int idx = par.indexOfChild(bar);
                par.removeView(bar);
                par.addView(f, idx);
            }
        });

        return bar;
    }

    private static void updateProgressState(int current, int total, boolean running) {
        if (sProgressBar == null || sProgressFill == null || sProgressText == null || sProgressCount == null) return;
        sProgressBar.post(() -> {
            float ratio = total > 0 ? (float)current / total : 0f;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) sProgressFill.getLayoutParams();
            lp.weight = ratio;
            sProgressFill.setLayoutParams(lp);
            sProgressFill.setBackgroundColor(running ? CLR_NEON : CLR_GRAY);
            sProgressText.setText(running ? "运行中" : "空闲");
            sProgressText.setTextColor(running ? CLR_NEON : CLR_GRAY);
            sProgressCount.setText("第" + current + "/" + total);
            sProgressCount.setTextColor(running ? CLR_HIGHLIGHT : CLR_GRAY);
            sEmergencyBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        });
    }

    // ===== 卡片1: 任务基础信息 =====

    private static View buildCard1(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "任务基础信息");

        final EditText nameEt = editText(ctx, d, "请输入任务名称", CLR_HIGHLIGHT);
        nameEt.setText(sTaskNameCache);
        card.addView(rowLabel(ctx, d, "任务名称", nameEt));
        card.addView(hSep(ctx, d));

        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView timeLabel = label(ctx, d, "发送时间");
        timeLabel.setLayoutParams(lpFixW(PX(d, 80)));
        timeRow.addView(timeLabel);

        final NumberPicker hourPk = new NumberPicker(ctx);
        hourPk.setMinValue(0); hourPk.setMaxValue(23); hourPk.setValue(sHourCache);
        styleNp(hourPk, ctx, d);
        timeRow.addView(hourPk);

        TextView colon = new TextView(ctx);
        colon.setText(":");
        colon.setTextSize(16); colon.setTextColor(CLR_WHITE); colon.setTypeface(null, Typeface.BOLD);
        colon.setPadding(PX(d, 4), 0, PX(d, 4), 0);
        timeRow.addView(colon);

        final NumberPicker minPk = new NumberPicker(ctx);
        minPk.setMinValue(0); minPk.setMaxValue(59); minPk.setValue(sMinuteCache);
        styleNp(minPk, ctx, d);
        timeRow.addView(minPk);

        View spT = new View(ctx); spT.setLayoutParams(lpWeight(1)); timeRow.addView(spT);

        card.addView(timeRow);
        card.addView(hSep(ctx, d));

        LinearLayout repeatRow = new LinearLayout(ctx);
        repeatRow.setOrientation(LinearLayout.HORIZONTAL);
        repeatRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rpLabel = label(ctx, d, "重复规则");
        rpLabel.setLayoutParams(lpFixW(PX(d, 80)));
        repeatRow.addView(rpLabel);

        final Spinner repeatSp = new Spinner(ctx);
        repeatSp.setAdapter(new ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, REPEAT_MODES) {
            @Override public View getView(int pos, View v, ViewGroup p) { View tv = super.getView(pos, v, p); ((TextView)tv).setTextColor(CLR_HIGHLIGHT); ((TextView)tv).setTextSize(12); return tv; }
            @Override public View getDropDownView(int pos, View v, ViewGroup p) { View tv = super.getDropDownView(pos, v, p); ((TextView)tv).setTextColor(CLR_WHITE); ((TextView)tv).setBackgroundColor(CLR_CARD); return tv; }
        });
        for (int i = 0; i < REPEAT_MODES.length; i++) { if (REPEAT_MODES[i].equals(sRepeatCache)) { repeatSp.setSelection(i); break; } }
        repeatRow.addView(repeatSp);
        View spR = new View(ctx); spR.setLayoutParams(lpWeight(1)); repeatRow.addView(spR);
        card.addView(repeatRow);
        card.addView(hSep(ctx, d));

        final CheckBox enableCb = checkBox(ctx, d, "启用此任务");
        card.addView(enableCb);

        card.setTag(new Object[]{nameEt, hourPk, minPk, repeatSp, enableCb});
        return card;
    }

    // ===== 卡片2: 消息内容配置 =====

    private static View buildCard2(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "消息内容配置");

        TextView typeTitle = new TextView(ctx);
        typeTitle.setText("选择消息类型");
        typeTitle.setTextSize(13);
        typeTitle.setTextColor(CLR_WHITE);
        typeTitle.setTypeface(null, Typeface.BOLD);
        typeTitle.setPadding(0, 0, 0, PX(d, 10));
        card.addView(typeTitle);

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout[] rows = new LinearLayout[4];
        for (int r = 0; r < 4; r++) {
            rows[r] = new LinearLayout(ctx);
            rows[r].setOrientation(LinearLayout.HORIZONTAL);
            rows[r].setPadding(0, 0, 0, r < 3 ? PX(d, 6) : 0);
            for (int c = 0; c < 3; c++) {
                final int idx = r * 3 + c;
                TextView btn = messageTypeButton(ctx, d, MSG_TYPES[idx], idx == sSelectedMsgType);
                btn.setLayoutParams(lpWeight(1));
                btn.setOnClickListener(v -> {
                    sSelectedMsgType = idx;
                    refreshMsgTypeGrid(rows);
                    checkXmlWarning(ctx, idx);
                });
                rows[r].addView(btn);
            }
            grid.addView(rows[r]);
        }
        card.addView(grid);
        card.addView(hSep(ctx, d));

        final EditText contentEt = editText(ctx, d, "输入消息内容...\n支持Emoji、{昵称}{群名称}{当前时间}变量\n多条文案用 | 分隔，发送时随机选取", CLR_WHITE);
        contentEt.setMinLines(3);
        contentEt.setText(sContentCache);
        card.addView(contentEt);

        LinearLayout previewRow = new LinearLayout(ctx);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.RIGHT);
        previewRow.setPadding(0, PX(d, 8), 0, 0);

        TextView previewBtn = new TextView(ctx);
        previewBtn.setText("预览消息");
        previewBtn.setTextSize(11);
        previewBtn.setTextColor(CLR_NEON);
        previewBtn.setPadding(PX(d, 14), PX(d, 6), PX(d, 14), PX(d, 6));
        GradientDrawable prevBg = new GradientDrawable();
        prevBg.setCornerRadius(PX(d, 14));
        prevBg.setStroke(PX(d, 1), CLR_NEON);
        prevBg.setColor(Color.TRANSPARENT);
        previewBtn.setBackground(prevBg);
        previewBtn.setOnClickListener(v -> showPreview(ctx, d, contentEt.getText().toString().trim(), sSelectedMsgType));
        previewRow.addView(previewBtn);
        card.addView(previewRow);

        card.setTag(new Object[]{rows, contentEt});
        return card;
    }

    private static void refreshMsgTypeGrid(LinearLayout[] rows) {
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 3; c++) {
                final int idx = r * 3 + c;
                View child = rows[r].getChildAt(c);
                if (child instanceof TextView) {
                    boolean sel = (idx == sSelectedMsgType);
                    ((TextView) child).setTextColor(sel ? Color.BLACK : CLR_NEON);
                    ((TextView) child).setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(PX(dp(child.getContext()), 8));
                    bg.setColor(sel ? CLR_NEON : CLR_CARD);
                    if (!sel) bg.setStroke(PX(dp(child.getContext()), 1), CLR_NEON);
                    child.setBackground(bg);
                    String txt = MSG_TYPES[idx];
                    ((TextView) child).setText(sel ? "  " + txt : txt);
                }
            }
        }
    }

    private static void checkXmlWarning(Context ctx, int msgType) {
        if (msgType == 11) {
            Toast.makeText(ctx, "提示: XML消息发送频率需谨慎控制，建议设置较长发送间隔", Toast.LENGTH_LONG).show();
        }
    }

    private static TextView messageTypeButton(Context ctx, float d, String text, boolean selected) {
        TextView btn = new TextView(ctx);
        btn.setText(selected ? "  " + text : text);
        btn.setTextSize(11);
        btn.setTextColor(selected ? Color.BLACK : CLR_NEON);
        btn.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(PX(d, 6), PX(d, 10), PX(d, 6), PX(d, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 8));
        bg.setColor(selected ? CLR_NEON : CLR_CARD);
        if (!selected) bg.setStroke(PX(d, 1), CLR_NEON);
        btn.setBackground(bg);
        return btn;
    }

    private static void showPreview(Context ctx, float d, String content, int msgType) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PX(d, 20), PX(d, 20), PX(d, 20), PX(d, 20));
        root.setBackgroundColor(CLR_BG);

        TextView title = new TextView(ctx);
        title.setText("消息预览");
        title.setTextSize(15); title.setTextColor(CLR_WHITE); title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, PX(d, 14));
        root.addView(title);

        TextView typeTv = new TextView(ctx);
        typeTv.setText("类型: " + MSG_TYPES[msgType]);
        typeTv.setTextSize(12); typeTv.setTextColor(CLR_HIGHLIGHT);
        typeTv.setPadding(0, 0, 0, PX(d, 10));
        root.addView(typeTv);

        TextView contentTv = new TextView(ctx);
        contentTv.setText(content.isEmpty() ? "(空消息)" : content);
        contentTv.setTextSize(13); contentTv.setTextColor(CLR_WHITE);
        contentTv.setPadding(PX(d, 12), PX(d, 12), PX(d, 12), PX(d, 12));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(PX(d, 8));
        cbg.setColor(CLR_CARD);
        contentTv.setBackground(cbg);
        root.addView(contentTv);

        b.setView(root);
        b.setCancelable(true);
        AlertDialog dlg = b.create();
        Window w = dlg.getWindow();
        if (w != null) { w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); }
        dlg.show();
    }

    // ===== 卡片3: 素材文件管理 =====

    private static View buildCard3(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "素材文件管理");

        LinearLayout uploadRow = new LinearLayout(ctx);
        uploadRow.setOrientation(LinearLayout.HORIZONTAL);
        uploadRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView uploadBtn = new TextView(ctx);
        uploadBtn.setText("+ 上传素材");
        uploadBtn.setTextSize(12);
        uploadBtn.setTextColor(CLR_NEON);
        uploadBtn.setPadding(PX(d, 14), PX(d, 8), PX(d, 14), PX(d, 8));
        GradientDrawable upBg = new GradientDrawable();
        upBg.setCornerRadius(PX(d, 6));
        upBg.setStroke(PX(d, 1), CLR_NEON);
        upBg.setColor(Color.TRANSPARENT);
        uploadBtn.setBackground(upBg);
        uploadRow.addView(uploadBtn);

        View spU = new View(ctx); spU.setLayoutParams(lpWeight(1)); uploadRow.addView(spU);

        final TextView countLabel = new TextView(ctx);
        countLabel.setText(sMaterialFiles.size() + " 个素材");
        countLabel.setTextSize(11);
        countLabel.setTextColor(CLR_HIGHLIGHT);
        uploadRow.addView(countLabel);

        card.addView(uploadRow);

        final LinearLayout previewArea = new LinearLayout(ctx);
        previewArea.setOrientation(LinearLayout.HORIZONTAL);
        previewArea.setPadding(0, PX(d, 8), 0, 0);
        card.addView(previewArea);

        uploadBtn.setOnClickListener(v -> showMaterialInput(ctx, d, previewArea, countLabel));
        refreshMaterialPreviews(ctx, d, previewArea, countLabel);

        card.setTag(new Object[]{previewArea, countLabel});
        return card;
    }

    private static void showMaterialInput(Context ctx, float d, LinearLayout previewArea, TextView countLabel) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PX(d, 16), PX(d, 16), PX(d, 16), PX(d, 16));
        root.setBackgroundColor(CLR_BG);

        TextView title = new TextView(ctx);
        title.setText("添加素材文件路径");
        title.setTextSize(14); title.setTextColor(CLR_WHITE); title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, PX(d, 10));
        root.addView(title);

        final EditText pathEt = editText(ctx, d, "输入文件完整路径\n多个文件用换行分隔\n如 /sdcard/Download/img.jpg", CLR_HIGHLIGHT);
        pathEt.setMinLines(3);
        root.addView(pathEt);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.RIGHT);
        btnRow.setPadding(0, PX(d, 10), 0, 0);

        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText("取消"); cancelBtn.setTextSize(12); cancelBtn.setTextColor(CLR_GRAY);
        cancelBtn.setPadding(PX(d, 14), PX(d, 8), PX(d, 14), PX(d, 8));
        btnRow.addView(cancelBtn);

        TextView okBtn = new TextView(ctx);
        okBtn.setText("添加"); okBtn.setTextSize(12); okBtn.setTextColor(CLR_NEON);
        okBtn.setPadding(PX(d, 14), PX(d, 8), PX(d, 14), PX(d, 8));
        btnRow.addView(okBtn);

        root.addView(btnRow);
        b.setView(root);
        AlertDialog dlg = b.create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        cancelBtn.setOnClickListener(v2 -> dlg.dismiss());
        okBtn.setOnClickListener(v2 -> {
            String paths = pathEt.getText().toString().trim();
            if (!paths.isEmpty()) {
                for (String line : paths.split("\\n")) {
                    String t = line.trim();
                    if (!t.isEmpty() && !sMaterialFiles.contains(t)) sMaterialFiles.add(t);
                }
                refreshMaterialPreviews(ctx, d, previewArea, countLabel);
            }
            dlg.dismiss();
        });
        dlg.show();
    }

    private static void refreshMaterialPreviews(Context ctx, float d, LinearLayout area, TextView countLabel) {
        area.removeAllViews();
        if (sMaterialFiles.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无素材");
            empty.setTextSize(11); empty.setTextColor(CLR_GRAY);
            area.addView(empty);
        } else {
            for (int i = 0; i < sMaterialFiles.size(); i++) {
                final String path = sMaterialFiles.get(i);
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (name.length() > 12) name = name.substring(0, 12) + "...";
                final int idx = i;

                LinearLayout item = new LinearLayout(ctx);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setGravity(Gravity.CENTER);
                item.setPadding(PX(d, 6), PX(d, 4), PX(d, 6), PX(d, 4));
                GradientDrawable itemBg = new GradientDrawable();
                itemBg.setCornerRadius(PX(d, 6));
                itemBg.setColor(CLR_CARD);
                itemBg.setStroke(PX(d, 1), 0x33336655);
                item.setBackground(itemBg);

                TextView icon = new TextView(ctx);
                icon.setText(fileIcon(path));
                icon.setTextSize(20);
                icon.setPadding(0, 0, 0, PX(d, 2));
                item.addView(icon);

                TextView fname = new TextView(ctx);
                fname.setText(name);
                fname.setTextSize(9);
                fname.setTextColor(CLR_WHITE);
                fname.setMaxWidth(PX(d, 60));
                item.addView(fname);

                item.setOnClickListener(v -> {
                    sMaterialFiles.remove(idx);
                    refreshMaterialPreviews(ctx, d, area, countLabel);
                });
                item.setOnLongClickListener(v -> {
                    Toast.makeText(ctx, path, Toast.LENGTH_SHORT).show();
                    return true;
                });

                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2, -2);
                ilp.setMargins(0, 0, PX(d, 6), 0);
                item.setLayoutParams(ilp);
                area.addView(item);
            }
        }
        countLabel.setText(sMaterialFiles.size() + " 个素材");
    }

    private static String fileIcon(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".jpeg")) return "\uD83D\uDDBC";
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mkv")) return "\uD83C\uDFAC";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".amr")) return "\uD83C\uDFB5";
        if (lower.endsWith(".pdf")) return "\uD83D\uDCC4";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "\uD83D\uDCC3";
        return "\uD83D\uDCC1";
    }

    // ===== 卡片4: 发送目标渠道 =====

    private static View buildCard4(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "发送目标渠道");

        LinearLayout channelRow = new LinearLayout(ctx);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView[] chButtons = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int ci = i;
            TextView cb = new TextView(ctx);
            cb.setText(CHANNELS[i]);
            cb.setTextSize(12);
            cb.setTextColor(i == sSelectedChannel ? Color.BLACK : CLR_NEON);
            cb.setTypeface(null, i == sSelectedChannel ? Typeface.BOLD : Typeface.NORMAL);
            cb.setGravity(Gravity.CENTER);
            cb.setPadding(PX(d, 8), PX(d, 8), PX(d, 8), PX(d, 8));
            GradientDrawable cbg = new GradientDrawable();
            cbg.setCornerRadius(PX(d, 6));
            cbg.setColor(i == sSelectedChannel ? CLR_NEON : CLR_CARD);
            if (i != sSelectedChannel) cbg.setStroke(PX(d, 1), CLR_NEON);
            cb.setBackground(cbg);
            cb.setLayoutParams(lpWeight(1));
            cb.setOnClickListener(v -> {
                sSelectedChannel = ci;
                for (int j = 0; j < 3; j++) {
                    chButtons[j].setTextColor(j == ci ? Color.BLACK : CLR_NEON);
                    chButtons[j].setTypeface(null, j == ci ? Typeface.BOLD : Typeface.NORMAL);
                    GradientDrawable g = new GradientDrawable();
                    g.setCornerRadius(PX(d, 6));
                    g.setColor(j == ci ? CLR_NEON : CLR_CARD);
                    if (j != ci) g.setStroke(PX(d, 1), CLR_NEON);
                    chButtons[j].setBackground(g);
                }
                refreshContactDisplay(ctx, d, card);
            });
            if (i > 0) { View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 6), 0)); channelRow.addView(sp); }
            channelRow.addView(cb);
            chButtons[i] = cb;
        }
        card.addView(channelRow);
        card.addView(hSep(ctx, d));

        LinearLayout selRow = new LinearLayout(ctx);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        selRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView selBtn = new TextView(ctx);
        selBtn.setText("选择联系人");
        selBtn.setTextSize(12);
        selBtn.setTextColor(CLR_NEON);
        selBtn.setPadding(PX(d, 14), PX(d, 8), PX(d, 14), PX(d, 8));
        GradientDrawable selBg = new GradientDrawable();
        selBg.setCornerRadius(PX(d, 6));
        selBg.setStroke(PX(d, 1), CLR_NEON);
        selBg.setColor(Color.TRANSPARENT);
        selBtn.setBackground(selBg);
        selRow.addView(selBtn);

        View spC = new View(ctx); spC.setLayoutParams(lpWeight(1)); selRow.addView(spC);

        final TextView countTv = new TextView(ctx);
        countTv.setText(sSelectedContacts.size() + " 个选中");
        countTv.setTextSize(11);
        countTv.setTextColor(CLR_HIGHLIGHT);
        selRow.addView(countTv);

        card.addView(selRow);

        selBtn.setOnClickListener(v -> {
            ContactPickerDialog.show(act, joinSet(",", sSelectedContacts),
                sSelectedChannel == 1 ? 1 : 0,
                (wxids, display) -> {
                    sSelectedContacts.clear();
                    sSelectedContacts.addAll(wxids);
                    countTv.setText(sSelectedContacts.size() + " 个选中");
                });
        });

        LinearLayout excRow = new LinearLayout(ctx);
        excRow.setOrientation(LinearLayout.HORIZONTAL);
        excRow.setGravity(Gravity.CENTER_VERTICAL);
        excRow.setPadding(0, PX(d, 8), 0, 0);

        TextView excBtn = new TextView(ctx);
        excBtn.setText("排除名单");
        excBtn.setTextSize(12);
        excBtn.setTextColor(CLR_RED);
        excBtn.setPadding(PX(d, 14), PX(d, 8), PX(d, 14), PX(d, 8));
        GradientDrawable excBg = new GradientDrawable();
        excBg.setCornerRadius(PX(d, 6));
        excBg.setStroke(PX(d, 1), CLR_RED);
        excBg.setColor(Color.TRANSPARENT);
        excBtn.setBackground(excBg);
        excRow.addView(excBtn);

        View spE = new View(ctx); spE.setLayoutParams(lpWeight(1)); excRow.addView(spE);

        final TextView excCountTv = new TextView(ctx);
        excCountTv.setText(sExcludeContacts.size() + " 个排除");
        excCountTv.setTextSize(11);
        excCountTv.setTextColor(CLR_RED);
        excRow.addView(excCountTv);

        card.addView(excRow);

        excBtn.setOnClickListener(v -> {
            ContactPickerDialog.show(act, joinSet(",", sExcludeContacts),
                sSelectedChannel == 1 ? 1 : 0,
                (wxids, display) -> {
                    sExcludeContacts.clear();
                    sExcludeContacts.addAll(wxids);
                    excCountTv.setText(sExcludeContacts.size() + " 个排除");
                });
        });

        // 朋友圈额外配置
        LinearLayout momentsExtra = new LinearLayout(ctx);
        momentsExtra.setOrientation(LinearLayout.VERTICAL);
        momentsExtra.setVisibility(sSelectedChannel == 2 ? View.VISIBLE : View.GONE);
        momentsExtra.setPadding(0, PX(d, 10), 0, 0);

        final EditText visibleEt = editText(ctx, d, "自定义可见范围（wxid逗号分隔，留空=全部好友可见）", CLR_HIGHLIGHT);
        visibleEt.setMinLines(1);
        momentsExtra.addView(visibleEt);

        final EditText locationEt = editText(ctx, d, "自定义虚拟定位（如: 北京市朝阳区XX路）", CLR_HIGHLIGHT);
        locationEt.setMinLines(1);
        locationEt.setPadding(0, PX(d, 8), 0, 0);
        momentsExtra.addView(locationEt);

        final EditText commentEt = editText(ctx, d, "发布完成后延时自动评论内容（留空=不评论）", CLR_HIGHLIGHT);
        commentEt.setMinLines(1);
        commentEt.setPadding(0, PX(d, 8), 0, 0);
        momentsExtra.addView(commentEt);

        final CheckBox autoDeleteCb = checkBox(ctx, d, "定时自动删除动态（24小时后）");
        momentsExtra.addView(autoDeleteCb);

        card.addView(momentsExtra);
        card.setTag(new Object[]{countTv, excCountTv, momentsExtra, visibleEt, locationEt, commentEt, autoDeleteCb});

        return card;
    }

    private static void refreshContactDisplay(Context ctx, float d, LinearLayout card) {
        Object[] tag = (Object[]) card.getTag();
        if (tag != null && tag.length >= 3 && tag[2] instanceof LinearLayout) {
            ((LinearLayout)tag[2]).setVisibility(sSelectedChannel == 2 ? View.VISIBLE : View.GONE);
        }
    }

    // ===== 卡片5: 风控间隔策略 =====

    private static View buildCard5(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "风控间隔策略");

        final EditText intervalEt = editText(ctx, d, "5", CLR_HIGHLIGHT);
        intervalEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        card.addView(rowLabel(ctx, d, "发送间隔(秒)", intervalEt));
        card.addView(hSep(ctx, d));

        final CheckBox randomCb = checkBox(ctx, d, "开启随机浮动延迟（实际间隔在设定值±30%范围随机）");
        card.addView(randomCb);
        card.addView(hSep(ctx, d));

        LinearLayout batchRow = new LinearLayout(ctx);
        batchRow.setOrientation(LinearLayout.HORIZONTAL);
        batchRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText batchSizeEt = editText(ctx, d, "10", CLR_HIGHLIGHT);
        batchSizeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        batchSizeEt.setLayoutParams(lpWeight(1));
        batchRow.addView(rowLabel(ctx, d, "每批发送", batchSizeEt));

        View spB1 = new View(ctx); spB1.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 12), 0)); batchRow.addView(spB1);

        final EditText batchIntEt = editText(ctx, d, "60", CLR_HIGHLIGHT);
        batchIntEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        batchIntEt.setLayoutParams(lpWeight(1));
        batchRow.addView(rowLabel(ctx, d, "批次间隔(秒)", batchIntEt));

        card.addView(batchRow);
        card.addView(hSep(ctx, d));

        final EditText maxSendEt = editText(ctx, d, "200", CLR_HIGHLIGHT);
        maxSendEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        card.addView(rowLabel(ctx, d, "单次任务最大发送", maxSendEt));

        card.setTag(new Object[]{intervalEt, randomCb, batchSizeEt, batchIntEt, maxSendEt});
        return card;
    }

    // ===== 卡片6: 高级策略设置 =====

    private static View buildCard6(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "高级策略设置");

        final EditText retryTimesEt = editText(ctx, d, "3", CLR_HIGHLIGHT);
        retryTimesEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        card.addView(rowLabel(ctx, d, "最大重试次数", retryTimesEt));
        card.addView(hSep(ctx, d));

        final EditText retryIntEt = editText(ctx, d, "60", CLR_HIGHLIGHT);
        retryIntEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        card.addView(rowLabel(ctx, d, "重试等待间隔(秒)", retryIntEt));

        TextView hint = new TextView(ctx);
        hint.setText("多次重试失败将自动标记该对象跳过，不阻断整体群发队列");
        hint.setTextSize(10); hint.setTextColor(CLR_GRAY);
        hint.setPadding(0, PX(d, 8), 0, 0);
        card.addView(hint);

        card.setTag(new Object[]{retryTimesEt, retryIntEt});
        return card;
    }

    // ===== 卡片7: 模板与历史日志 =====

    private static View buildCard7(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "模板与历史日志");

        LinearLayout tplRow = new LinearLayout(ctx);
        tplRow.setOrientation(LinearLayout.HORIZONTAL);
        tplRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView saveTplBtn = new TextView(ctx);
        saveTplBtn.setText("保存为模板");
        saveTplBtn.setTextSize(12);
        saveTplBtn.setTextColor(CLR_NEON);
        saveTplBtn.setPadding(PX(d, 12), PX(d, 8), PX(d, 12), PX(d, 8));
        GradientDrawable stBg = new GradientDrawable();
        stBg.setCornerRadius(PX(d, 6));
        stBg.setStroke(PX(d, 1), CLR_NEON);
        stBg.setColor(Color.TRANSPARENT);
        saveTplBtn.setBackground(stBg);
        tplRow.addView(saveTplBtn);

        View spT1 = new View(ctx); spT1.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 8), 0)); tplRow.addView(spT1);

        TextView loadTplBtn = new TextView(ctx);
        loadTplBtn.setText("加载模板");
        loadTplBtn.setTextSize(12);
        loadTplBtn.setTextColor(CLR_WHITE);
        loadTplBtn.setPadding(PX(d, 12), PX(d, 8), PX(d, 12), PX(d, 8));
        GradientDrawable ltBg = new GradientDrawable();
        ltBg.setCornerRadius(PX(d, 6));
        ltBg.setStroke(PX(d, 1), CLR_WHITE);
        ltBg.setColor(Color.TRANSPARENT);
        loadTplBtn.setBackground(ltBg);
        tplRow.addView(loadTplBtn);

        card.addView(tplRow);
        card.addView(hSep(ctx, d));

        LinearLayout logRow = new LinearLayout(ctx);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView logBtn = new TextView(ctx);
        logBtn.setText("发送历史日志");
        logBtn.setTextSize(12);
        logBtn.setTextColor(CLR_YELLOW);
        logBtn.setPadding(PX(d, 12), PX(d, 8), PX(d, 12), PX(d, 8));
        GradientDrawable lbBg = new GradientDrawable();
        lbBg.setCornerRadius(PX(d, 6));
        lbBg.setStroke(PX(d, 1), CLR_YELLOW);
        lbBg.setColor(Color.TRANSPARENT);
        logBtn.setBackground(lbBg);
        logRow.addView(logBtn);

        View spL = new View(ctx); spL.setLayoutParams(lpWeight(1)); logRow.addView(spL);

        final TextView logCount = new TextView(ctx);
        List<SendLogEntry> logs = ScheduleBroadcast.getSendLogs();
        List<SendLogEntry> failed = ScheduleBroadcast.getFailedLogs();
        logCount.setText("共" + logs.size() + "条 / 失败" + failed.size() + "条");
        logCount.setTextSize(10);
        logCount.setTextColor(CLR_HIGHLIGHT);
        logRow.addView(logCount);

        card.addView(logRow);

        saveTplBtn.setOnClickListener(v -> showSaveTemplate(ctx, d, act));
        loadTplBtn.setOnClickListener(v -> showLoadTemplate(ctx, d, act));
        logBtn.setOnClickListener(v -> showSendLogs(ctx, d, act));

        card.setTag(logCount);
        return card;
    }

    private static void showSaveTemplate(Context ctx, float d, Activity act) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PX(d, 16), PX(d, 16), PX(d, 16), PX(d, 16));
        root.setBackgroundColor(CLR_BG);

        TextView title = new TextView(ctx);
        title.setText("保存消息模板"); title.setTextSize(14); title.setTextColor(CLR_WHITE);
        title.setTypeface(null, Typeface.BOLD); title.setPadding(0, 0, 0, PX(d, 10));
        root.addView(title);

        final EditText nameEt = editText(ctx, d, "模板名称", CLR_HIGHLIGHT);
        root.addView(nameEt);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.RIGHT);
        btnRow.setPadding(0, PX(d, 10), 0, 0);
        TextView cancel = new TextView(ctx);
        cancel.setText("取消"); cancel.setTextSize(12); cancel.setTextColor(CLR_GRAY);
        cancel.setPadding(PX(d, 14), PX(d, 8), PX(d, 14), PX(d, 8));
        btnRow.addView(cancel);
        TextView ok = new TextView(ctx);
        ok.setText("保存"); ok.setTextSize(12); ok.setTextColor(CLR_NEON);
        ok.setPadding(PX(d, 14), PX(d, 8), PX(d, 14), PX(d, 8));
        btnRow.addView(ok);
        root.addView(btnRow);

        b.setView(root);
        AlertDialog dlg = b.create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        cancel.setOnClickListener(v2 -> dlg.dismiss());
        ok.setOnClickListener(v2 -> {
            String name = nameEt.getText().toString().trim();
            if (name.isEmpty()) { Toast.makeText(ctx, "请输入模板名称", Toast.LENGTH_SHORT).show(); return; }
            TemplateData tpl = new TemplateData();
            tpl.name = name;
            tpl.content = sContentCache;
            tpl.msgType = MSG_TYPE_CODES[sSelectedMsgType];
            tpl.channel = sSelectedChannel;
            tpl.targetWxids = joinSet(",", sSelectedContacts);
            tpl.excludeWxids = joinSet(",", sExcludeContacts);
            ScheduleBroadcast.saveTemplate(tpl);
            Toast.makeText(ctx, "模板已保存", Toast.LENGTH_SHORT).show();
            dlg.dismiss();
        });
        dlg.show();
    }

    private static void showLoadTemplate(Context ctx, float d, Activity act) {
        List<TemplateData> templates = ScheduleBroadcast.getAllTemplates();
        if (templates.isEmpty()) { Toast.makeText(ctx, "暂无模板", Toast.LENGTH_SHORT).show(); return; }

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PX(d, 16), PX(d, 16), PX(d, 16), PX(d, 16));
        root.setBackgroundColor(CLR_BG);

        TextView title = new TextView(ctx);
        title.setText("加载模板"); title.setTextSize(14); title.setTextColor(CLR_WHITE);
        title.setTypeface(null, Typeface.BOLD); title.setPadding(0, 0, 0, PX(d, 10));
        root.addView(title);

        for (final TemplateData tpl : templates) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(PX(d, 10), PX(d, 8), PX(d, 10), PX(d, 8));

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(lpWeight(1));

            TextView tname = new TextView(ctx);
            tname.setText(tpl.name);
            tname.setTextSize(13); tname.setTextColor(CLR_WHITE);
            col.addView(tname);

            TextView ttype = new TextView(ctx);
            ttype.setText(MSG_TYPES[Math.min(sSelectedMsgType, 11)] + " | " + CHANNELS[Math.max(0, Math.min(2, tpl.channel))]);
            ttype.setTextSize(10); ttype.setTextColor(CLR_GRAY);
            col.addView(ttype);

            row.addView(col);

            TextView loadBtn = new TextView(ctx);
            loadBtn.setText("加载");
            loadBtn.setTextSize(11); loadBtn.setTextColor(CLR_NEON);
            loadBtn.setPadding(PX(d, 10), PX(d, 6), PX(d, 10), PX(d, 6));
            GradientDrawable ldBg = new GradientDrawable();
            ldBg.setCornerRadius(PX(d, 4));
            ldBg.setStroke(PX(d, 1), CLR_NEON);
            ldBg.setColor(Color.TRANSPARENT);
            loadBtn.setBackground(ldBg);

            final String tplId = tpl.id;
            loadBtn.setOnClickListener(v -> {
                sContentCache = tpl.content != null ? tpl.content : "";
                sSelectedChannel = tpl.channel;
                if (tpl.targetWxids != null && !tpl.targetWxids.isEmpty()) {
                    sSelectedContacts.clear();
                    for (String w : tpl.targetWxids.split(",")) { String tr = w.trim(); if (!tr.isEmpty()) sSelectedContacts.add(tr); }
                }
                if (tpl.excludeWxids != null && !tpl.excludeWxids.isEmpty()) {
                    sExcludeContacts.clear();
                    for (String w : tpl.excludeWxids.split(",")) { String tr = w.trim(); if (!tr.isEmpty()) sExcludeContacts.add(tr); }
                }
                SubPageActivity.open(act, "定时消息群发", 14);
            });
            row.addView(loadBtn);

            View spD = new View(ctx); spD.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 6), 0)); row.addView(spD);

            TextView delBtn = new TextView(ctx);
            delBtn.setText("删");
            delBtn.setTextSize(11); delBtn.setTextColor(CLR_RED);
            delBtn.setPadding(PX(d, 10), PX(d, 6), PX(d, 10), PX(d, 6));
            delBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.deleteTemplate(tplId);
                SubPageActivity.open(act, "定时消息群发", 14);
            });
            row.addView(delBtn);

            root.addView(row);
            View sep = new View(ctx);
            sep.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            sep.setBackgroundColor(0x22336655);
            root.addView(sep);
        }

        b.setView(root);
        AlertDialog dlg = b.create();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dlg.show();
    }

    private static void showSendLogs(Context ctx, float d, Activity act) {
        List<SendLogEntry> logs = ScheduleBroadcast.getSendLogs();
        List<SendLogEntry> failed = ScheduleBroadcast.getFailedLogs();

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PX(d, 16), PX(d, 16), PX(d, 16), PX(d, 16));
        root.setBackgroundColor(CLR_BG);

        TextView title = new TextView(ctx);
        title.setText("发送历史日志"); title.setTextSize(14); title.setTextColor(CLR_WHITE);
        title.setTypeface(null, Typeface.BOLD); title.setPadding(0, 0, 0, PX(d, 6));
        root.addView(title);

        TextView summary = new TextView(ctx);
        summary.setText("全部: " + logs.size() + "条 失败: " + failed.size() + "条");
        summary.setTextSize(11); summary.setTextColor(CLR_HIGHLIGHT);
        summary.setPadding(0, 0, 0, PX(d, 10));
        root.addView(summary);

        if (failed.size() > 0) {
            TextView resendBtn = new TextView(ctx);
            resendBtn.setText("一键补发全部失败对象");
            resendBtn.setTextSize(12);
            resendBtn.setTextColor(CLR_RED);
            resendBtn.setPadding(PX(d, 14), PX(d, 8), PX(d, 14), PX(d, 8));
            GradientDrawable rsBg = new GradientDrawable();
            rsBg.setCornerRadius(PX(d, 6));
            rsBg.setStroke(PX(d, 1), CLR_RED);
            rsBg.setColor(Color.TRANSPARENT);
            resendBtn.setBackground(rsBg);
            resendBtn.setOnClickListener(v -> {
                for (SendLogEntry e : failed) {
                    Task retryTask = new Task();
                    retryTask.msgType = MSG_TYPE_CODES[sSelectedMsgType];
                    retryTask.content = e.content;
                    retryTask.targetGroups.add(e.targetWxid);
                    retryTask.sendAllGroups = false;
                    ScheduleBroadcast.addTask(retryTask);
                }
                ScheduleBroadcast.clearSendLogs();
                Toast.makeText(ctx, "已创建补发任务", Toast.LENGTH_SHORT).show();
            });
            root.addView(resendBtn);
            View lsep = new View(ctx);
            lsep.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            lsep.setBackgroundColor(0x22336655);
            ((LinearLayout.LayoutParams)lsep.getLayoutParams()).setMargins(0, PX(d, 8), 0, PX(d, 8));
            root.addView(lsep);
        }

        ScrollView logSv = new ScrollView(ctx);
        logSv.setLayoutParams(new LinearLayout.LayoutParams(-1, PX(d, 350)));

        LinearLayout logList = new LinearLayout(ctx);
        logList.setOrientation(LinearLayout.VERTICAL);

        if (logs.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无发送记录");
            empty.setTextSize(12); empty.setTextColor(CLR_GRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, PX(d, 30), 0, 0);
            logList.addView(empty);
        }

        int shown = 0;
        java.text.SimpleDateFormat sdfLog = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        for (SendLogEntry e : logs) {
            if (shown++ >= 100) break;
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(PX(d, 8), PX(d, 6), PX(d, 8), PX(d, 6));

            LinearLayout top = new LinearLayout(ctx);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView statusIcon = new TextView(ctx);
            statusIcon.setText(e.success ? "\u2713" : "\u2717");
            statusIcon.setTextSize(12);
            statusIcon.setTextColor(e.success ? CLR_NEON : CLR_RED);
            statusIcon.setPadding(0, 0, PX(d, 8), 0);
            top.addView(statusIcon);

            TextView tname = new TextView(ctx);
            tname.setText((e.taskName != null && !e.taskName.isEmpty() ? e.taskName : "任务") + " -> " + (e.targetName != null ? e.targetName : e.targetWxid));
            tname.setTextSize(11);
            tname.setTextColor(CLR_WHITE);
            tname.setLayoutParams(lpWeight(1));
            top.addView(tname);

            TextView timeTv = new TextView(ctx);
            timeTv.setText(sdfLog.format(new Date(e.timestamp)));
            timeTv.setTextSize(9);
            timeTv.setTextColor(CLR_GRAY);
            top.addView(timeTv);

            item.addView(top);

            if (e.error != null && !e.error.isEmpty()) {
                TextView errTv = new TextView(ctx);
                errTv.setText("错误: " + e.error);
                errTv.setTextSize(10);
                errTv.setTextColor(CLR_RED);
                errTv.setPadding(PX(d, 20), PX(d, 2), 0, 0);
                item.addView(errTv);
            }

            logList.addView(item);
            View sep = new View(ctx);
            sep.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            sep.setBackgroundColor(0x15336655);
            logList.addView(sep);
        }

        logSv.addView(logList);
        root.addView(logSv);

        b.setView(root);
        AlertDialog dlg = b.create();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.90), -2);
        }
        dlg.show();
    }

    // ===== 底部操作按钮 =====

    private static View buildBottomBtn(Context ctx, float d, Activity act) {
        TextView btn = new TextView(ctx);
        btn.setText(sEditingTaskId != null ? "   更新并启用" : "   保存并启用");
        btn.setTextSize(15);
        btn.setTextColor(Color.BLACK);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(PX(d, 20), PX(d, 14), PX(d, 20), PX(d, 14));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(PX(d, 24));
        btnBg.setColor(CLR_NEON);
        btn.setBackground(btnBg);

        btn.setOnClickListener(v -> saveAndStart(ctx, d, act));
        return btn;
    }

    // ===== 保存并启动任务 =====

    private static void saveAndStart(Context ctx, float d, Activity act) {
        try {
            Task task;
            if (sEditingTaskId != null) {
                task = ScheduleBroadcast.getTask(sEditingTaskId);
                if (task == null) task = new Task();
            } else {
                task = new Task();
            }

            task.msgType = MSG_TYPE_CODES[sSelectedMsgType];

            String content = sContentCache;
            if (content.isEmpty()) { Toast.makeText(ctx, "请输入消息内容", Toast.LENGTH_SHORT).show(); return; }
            task.content = content;

            String name = sTaskNameCache;
            if (name.isEmpty()) name = "定时任务_" + new SimpleDateFormat("MMddHHmm", Locale.getDefault()).format(new Date());

            task.targetGroups = new ArrayList<>();
            if (sSelectedChannel == 0) {
                for (String w : sSelectedContacts) { if (!w.endsWith("@chatroom")) task.targetGroups.add(w); }
            } else if (sSelectedChannel == 1) {
                for (String w : sSelectedContacts) { task.targetGroups.add(w); }
            }
            task.sendAllGroups = sSelectedContacts.isEmpty() && sSelectedChannel == 1;

            if (!task.sendAllGroups && task.targetGroups.isEmpty()) { Toast.makeText(ctx, "请选择发送目标", Toast.LENGTH_SHORT).show(); return; }

            // 素材文件路径
            if (!sMaterialFiles.isEmpty()) {
                task.filePath = sMaterialFiles.get(0);
            }

            // 语言设置
            task.varNickname = content.contains("{昵称}");
            task.varGroupName = content.contains("{群名称}");
            task.varTime = content.contains("{当前时间}");
            task.varDate = content.contains("{date}");

            // 计算触发时间
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, sHourCache);
            cal.set(Calendar.MINUTE, sMinuteCache);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1);
            task.triggerTime = cal.getTimeInMillis();

            // 重复模式
            if ("每日循环".equals(sRepeatCache)) task.repeatInterval = 86400000L;
            else if ("每周循环".equals(sRepeatCache)) task.repeatInterval = 604800000L;
            else task.repeatInterval = 0;

            task.enabled = true;
            task.totalSendCount = 0;
            task.failCount = 0;

            if (sEditingTaskId != null) {
                task.id = sEditingTaskId;
                ScheduleBroadcast.updateTask(task);
                Toast.makeText(ctx, "任务已更新", Toast.LENGTH_SHORT).show();
            } else {
                ScheduleBroadcast.addTask(task);
                Toast.makeText(ctx, "任务已保存并启用", Toast.LENGTH_SHORT).show();
            }

            autoSaveDraft(ctx);
            sEditingTaskId = null;

            SubPageActivity.open(act, "定时消息群发", 14);

        } catch (Throwable t) {
            Toast.makeText(ctx, "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ===== 草稿自动保存 =====

    private static void autoSaveDraft(Context ctx) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("schedule_draft_auto", Context.MODE_PRIVATE);
            sp.edit()
                .putString("taskName", sTaskNameCache)
                .putString("content", sContentCache)
                .putInt("hour", sHourCache)
                .putInt("minute", sMinuteCache)
                .putString("repeat", sRepeatCache)
                .putInt("msgType", sSelectedMsgType)
                .putInt("channel", sSelectedChannel)
                .putString("contacts", joinSet(",", sSelectedContacts))
                .putString("exclude", joinSet(",", sExcludeContacts))
                .commit();
        } catch (Throwable ignored) {}
    }

    private static void loadDraft(Context ctx) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("schedule_draft_auto", Context.MODE_PRIVATE);
            sTaskNameCache = sp.getString("taskName", "");
            sContentCache = sp.getString("content", "");
            sHourCache = sp.getInt("hour", 8);
            sMinuteCache = sp.getInt("minute", 0);
            sRepeatCache = sp.getString("repeat", "仅一次");
            sSelectedMsgType = sp.getInt("msgType", 0);
            sSelectedChannel = sp.getInt("channel", 1);
            String cs = sp.getString("contacts", "");
            sSelectedContacts.clear();
            if (cs != null && !cs.isEmpty()) for (String w : cs.split(",")) { String t = w.trim(); if (!t.isEmpty()) sSelectedContacts.add(t); }
            String ex = sp.getString("exclude", "");
            sExcludeContacts.clear();
            if (ex != null && !ex.isEmpty()) for (String w : ex.split(",")) { String t = w.trim(); if (!t.isEmpty()) sExcludeContacts.add(t); }
        } catch (Throwable ignored) {}
    }

    // ===== UI 构建工具 =====

    private static LinearLayout makeCard(Context ctx, float d, String title) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(PX(d, 14), PX(d, 14), PX(d, 14), PX(d, 14));
        card.setClipToOutline(true);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(PX(d, 12));
        cardBg.setStroke(PX(d, 1), CLR_NEON);
        cardBg.setColor(CLR_CARD);
        card.setBackground(cardBg);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView iv = new TextView(ctx);
        iv.setTextColor(CLR_NEON);
        iv.setTextSize(10);
        iv.setPadding(0, 0, PX(d, 8), 0);
        String icon = title.contains("任务基础") ? "\u2139" : title.contains("消息内容") ? "\u2709" :
            title.contains("素材文件") ? "\uD83D\uDCC1" : title.contains("发送目标") ? "\uD83C\uDFAF" :
            title.contains("风控") ? "\u26A1" : title.contains("高级") ? "\u2699" : "\uD83D\uDCCB";
        iv.setText(icon);
        header.addView(iv);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(CLR_WHITE);
        t.setTypeface(null, Typeface.BOLD);
        t.setLayoutParams(lpWeight(1));
        header.addView(t);

        card.addView(header);

        View div = new View(ctx);
        div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        div.setBackgroundColor(0x22336655);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 1);
        dlp.setMargins(0, PX(d, 8), 0, PX(d, 10));
        div.setLayoutParams(dlp);
        card.addView(div);

        return card;
    }

    private static LinearLayout rowLabel(Context ctx, float d, String labelText, View widget) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, PX(d, 4), 0, PX(d, 4));

        TextView tv = label(ctx, d, labelText);
        tv.setLayoutParams(lpFixW(PX(d, 80)));
        row.addView(tv);

        row.addView(widget);
        return row;
    }

    private static TextView label(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(CLR_WHITE);
        tv.setPadding(0, 0, PX(d, 8), 0);
        return tv;
    }

    private static EditText editText(Context ctx, float d, String hint, int textColor) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(CLR_GRAY);
        et.setTextColor(textColor);
        et.setBackgroundColor(Color.TRANSPARENT);
        et.setPadding(PX(d, 10), PX(d, 6), PX(d, 10), PX(d, 6));
        et.setTextSize(12);
        et.setSingleLine(false);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        return et;
    }

    private static CheckBox checkBox(Context ctx, float d, String text) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(text);
        cb.setTextSize(12);
        cb.setTextColor(CLR_WHITE);
        return cb;
    }

    private static View hSep(Context ctx, float d) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        v.setBackgroundColor(0x18336655);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, 1);
        vlp.setMargins(0, PX(d, 6), 0, PX(d, 6));
        v.setLayoutParams(vlp);
        return v;
    }

    private static View vSpacer(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, PX(d, dp)));
        return v;
    }

    private static void styleNp(NumberPicker np, Context ctx, float d) {
        np.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 60), -2));
    }

    private static Path buildChamferPath(RectF rect, float chamfer) {
        Path path = new Path();
        path.moveTo(rect.left + chamfer, rect.top);
        path.lineTo(rect.right, rect.top);
        path.lineTo(rect.right - chamfer, rect.bottom);
        path.lineTo(rect.left, rect.bottom);
        path.close();
        return path;
    }

    // ===== 工具方法 =====

    private static LinearLayout.LayoutParams lpWeight(int weight) { return new LinearLayout.LayoutParams(0, -2, weight); }
    private static LinearLayout.LayoutParams lpWeight(float weight) { return new LinearLayout.LayoutParams(0, -2, weight); }
    private static LinearLayout.LayoutParams lpFixW(int w) { return new LinearLayout.LayoutParams(w, -2); }
    private static int PX(float d, int dp) { return (int)(dp * d); }
    private static float dp(Context ctx) { return ctx.getResources().getDisplayMetrics().density; }
    private static String nvl(String s) { return s == null ? "" : s; }

    private static String joinSet(CharSequence delimiter, Set<String> tokens) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String token : tokens) {
            if (first) first = false; else sb.append(delimiter);
            sb.append(token);
        }
        return sb.toString();
    }
}
