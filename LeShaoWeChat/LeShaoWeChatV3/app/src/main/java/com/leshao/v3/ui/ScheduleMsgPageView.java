package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    private static final String[] MSG_TYPES = {"文本消息","图片消息","视频消息","图文消息","语音消息","位置消息","艾特公告","名片消息","文件消息"};
    private static final int[] MSG_TYPE_CODES = {1, 3, 43, 49, 34, 48, 1, 42, 6};
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
    private static int sYearCache = 0;
    private static int sMonthCache = 0;
    private static int sDayCache = 0;
    private static String sRepeatCache = "仅一次";

    private static View sProgressBar;
    private static View sProgressFill;
    private static TextView sProgressText;
    private static TextView sProgressCount;
    private static TextView sEmergencyBtn;
    private static FrameLayout sProgressRoot;
    private static LinearLayout sTypeContentContainer;
    private static java.lang.ref.WeakReference<Activity> sActRef;
    private static EditText sNameEt, sYearEt, sMonEt, sDayEt, sHourEt, sMinEt, sSecEt;

    private static ContactPickerDialog.OnContactsSelected sLastContactsCallback;
    private static Activity sParentActivity;

    // ===== 文件/图片选取拦截 =====
    private static final int REQ_IMAGE_PICK = 9010;
    private static final int REQ_FILE_PICK = 9011;
    private static final int REQ_MATERIAL_PICK = 9012;
    private static final List<String> sPickedFiles = new ArrayList<>();
    private static Runnable sOnFilePicked;
    private static java.io.File sHookFileDest;
    private static boolean sHookRegistered = false;

    private static void registerFilePickHook() {
        if (sHookRegistered) return;
        sHookRegistered = true;
        try {
            java.lang.reflect.Method method = Activity.class.getDeclaredMethod("onActivityResult", int.class, int.class, android.content.Intent.class);
            de.robv.android.xposed.XposedBridge.hookMethod(method, new de.robv.android.xposed.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int req = (int) param.args[0];
                        int res = (int) param.args[1];
                        android.content.Intent data = (android.content.Intent) param.args[2];
                        if (res != Activity.RESULT_OK || data == null) return;
                        Activity act = (Activity) param.thisObject;

                        if (req == REQ_IMAGE_PICK || req == REQ_FILE_PICK || req == REQ_MATERIAL_PICK) {
                            handleFilePickResult(act, data, req);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { LogWriter.log("SCHEDULE_MSG", "hook err: " + t.getMessage()); }
    }

    private static void handleFilePickResult(Activity act, android.content.Intent data, int req) {
        try {
            List<android.net.Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (android.net.Uri uri : uris) {
                String path = copyUriToFile(act, uri, "leshao_pick_" + System.currentTimeMillis() + "_" + sPickedFiles.size());
                if (path != null && !path.isEmpty()) sPickedFiles.add(path);
            }
            if (sOnFilePicked != null) sOnFilePicked.run();
        } catch (Throwable t) { LogWriter.log("SCHEDULE_MSG", "pick err: " + t.getMessage()); }
    }

    private static String copyUriToFile(Context ctx, android.net.Uri uri, String name) {
        try {
            java.io.File dir = new java.io.File(ctx.getFilesDir(), "leshao_files");
            dir.mkdirs();
            java.io.File dest = new java.io.File(dir, name);
            java.io.InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close(); is.close();
            return dest.getAbsolutePath();
        } catch (Throwable t) { return null; }
    }

    private static void startImagePicker(Activity act) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            i.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            act.startActivityForResult(i, REQ_IMAGE_PICK);
        } catch (Throwable t) { Toast.makeText(act, "无法打开相册", Toast.LENGTH_SHORT).show(); }
    }

    private static void startFilePicker(Activity act, String mime) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType(mime != null ? mime : "*/*");
            i.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            act.startActivityForResult(i, REQ_FILE_PICK);
        } catch (Throwable t) { Toast.makeText(act, "无法打开文件管理器", Toast.LENGTH_SHORT).show(); }
    }

    private static void startMaterialPicker(Activity act) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            act.startActivityForResult(i, REQ_MATERIAL_PICK);
        } catch (Throwable t) { Toast.makeText(act, "无法打开文件管理器", Toast.LENGTH_SHORT).show(); }
    }

    public static View create(Context ctx, Activity parentAct) {
        try {
            sParentActivity = parentAct;
            sActRef = new java.lang.ref.WeakReference<>(parentAct);
            float d = dp(ctx);
            LogWriter.log("SCHEDULE_MSG", "create: START, d=" + d);

            registerFilePickHook();

            LinearLayout body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setBackgroundColor(CLR_BG);
            body.setPadding(PX(d, 8), PX(d, 10), PX(d, 8), PX(d, 16));

            LogWriter.log("SCHEDULE_MSG", "create: buildProgressBar...");
            body.addView(buildProgressBar(ctx, d));
            LogWriter.log("SCHEDULE_MSG", "create: buildCard1...");
            body.addView(vSpacer(ctx, d, 6));
            body.addView(buildCard1(ctx, d, parentAct));
            LogWriter.log("SCHEDULE_MSG", "create: buildCard2...");
            body.addView(vSpacer(ctx, d, 6));
            body.addView(buildCard2(ctx, d, parentAct));
            LogWriter.log("SCHEDULE_MSG", "create: buildCard3...");
            body.addView(vSpacer(ctx, d, 6));
            body.addView(buildCard3(ctx, d, parentAct));
            LogWriter.log("SCHEDULE_MSG", "create: buildCard4...");
            body.addView(vSpacer(ctx, d, 6));
            body.addView(buildCard4(ctx, d, parentAct));
            LogWriter.log("SCHEDULE_MSG", "create: buildCard5...");
            body.addView(vSpacer(ctx, d, 6));
            body.addView(buildCard5(ctx, d, parentAct));
            LogWriter.log("SCHEDULE_MSG", "create: buildCard6...");
            body.addView(vSpacer(ctx, d, 6));
            body.addView(buildCard6(ctx, d, parentAct));
            LogWriter.log("SCHEDULE_MSG", "create: buildCard7...");
            body.addView(vSpacer(ctx, d, 6));
            body.addView(buildCard7(ctx, d, parentAct));
            LogWriter.log("SCHEDULE_MSG", "create: buildBottomBtn...");
            body.addView(vSpacer(ctx, d, 10));
            body.addView(buildBottomBtn(ctx, d, parentAct));

            loadDraft(ctx);

            LogWriter.log("SCHEDULE_MSG", "create: DONE OK");
            ScrollView sv = new ScrollView(ctx);
            sv.setFillViewport(true);
            sv.setBackgroundColor(CLR_BG);
            sv.addView(body);
            return sv;
        } catch (Throwable t) {
            LogWriter.log("SCHEDULE_MSG", "create: CRASH: " + t.getClass().getName() + ": " + t.getMessage());
            throw new RuntimeException(t);
        }
    }

    // ===== 进度状态栏 =====

    private static View buildProgressBar(Context ctx, float d) {
        int barW = ctx.getResources().getDisplayMetrics().widthPixels - PX(d, 20);
        int barH = PX(d, 36);

        FrameLayout root = new FrameLayout(ctx);
        root.setLayoutParams(new LinearLayout.LayoutParams(barW, barH));
        root.setClipToOutline(true);
        root.setVisibility(View.GONE);
        sProgressRoot = root;

        sProgressBar = new View(ctx) {
            @Override
            protected void onDraw(Canvas canvas) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(CLR_DARK);
                Path path = buildChamferPath(new RectF(0, 0, getWidth(), getHeight()), PX(d, 8));
                canvas.drawPath(path, p);
            }
        };
        root.addView(sProgressBar, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout overlay = new LinearLayout(ctx);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(PX(d, 14), 0, PX(d, 10), 0);

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
        stopBg.setCornerRadius(PX(d, 2));
        stopBg.setColor(CLR_RED);
        sEmergencyBtn.setBackground(stopBg);
        sEmergencyBtn.setVisibility(View.GONE);
        sEmergencyBtn.setOnClickListener(v -> {
            for (ScheduleBroadcast.Task t : ScheduleBroadcast.getAllTasks()) {
                ScheduleBroadcast.cancelSchedule(t);
            }
            sEmergencyBtn.setVisibility(View.GONE);
            updateProgressState(0, 0, false);
        });
        overlay.addView(sEmergencyBtn);

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        return root;
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
        stopBg.setCornerRadius(PX(d, 2));
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
        if (sProgressRoot != null) sProgressRoot.setVisibility(running ? View.VISIBLE : View.GONE);
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
        LinearLayout card = makeCard(ctx, d, "");

        final EditText nameEt = borderedEditText(ctx, d, "请输入任务名称", CLR_HIGHLIGHT);
        nameEt.setText(sTaskNameCache);
        card.addView(rowLabelW(ctx, d, "任务名称", nameEt, 52));
        card.addView(hSep(ctx, d));

        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView timeLabel = label(ctx, d, "发送时间");
        timeLabel.setLayoutParams(lpFixW(PX(d, 72)));
        timeRow.addView(timeLabel);
        View tsp1 = new View(ctx); tsp1.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 4), 0)); timeRow.addView(tsp1);

        Calendar now = Calendar.getInstance();
        if (sYearCache == 0) { sYearCache = now.get(Calendar.YEAR); sMonthCache = now.get(Calendar.MONTH)+1; sDayCache = now.get(Calendar.DAY_OF_MONTH); }

        final EditText yearEt = smallBorderedEdit(ctx, d, String.valueOf(sYearCache), CLR_HIGHLIGHT);
        yearEt.setLayoutParams(lpFixW(PX(d, 40)));
        timeRow.addView(yearEt);
        TextView yUnit = new TextView(ctx); yUnit.setText("年"); yUnit.setTextSize(10); yUnit.setTextColor(CLR_WHITE);
        timeRow.addView(yUnit);
        final EditText monEt = smallBorderedEdit(ctx, d, String.format("%02d", sMonthCache), CLR_HIGHLIGHT);
        monEt.setLayoutParams(lpFixW(PX(d, 32)));
        timeRow.addView(monEt);
        TextView mUnit = new TextView(ctx); mUnit.setText("月"); mUnit.setTextSize(10); mUnit.setTextColor(CLR_WHITE);
        timeRow.addView(mUnit);
        final EditText dayEt = smallBorderedEdit(ctx, d, String.format("%02d", sDayCache), CLR_HIGHLIGHT);
        dayEt.setLayoutParams(lpFixW(PX(d, 32)));
        timeRow.addView(dayEt);
        TextView dUnit = new TextView(ctx); dUnit.setText("日 "); dUnit.setTextSize(10); dUnit.setTextColor(CLR_WHITE);
        timeRow.addView(dUnit);

        final EditText hourEt = smallBorderedEdit(ctx, d, String.format("%02d", sHourCache), CLR_HIGHLIGHT);
        hourEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        hourEt.setLayoutParams(lpFixW(PX(d, 32)));
        hourEt.setText(String.format("%02d", sHourCache));
        timeRow.addView(hourEt);
        TextView hUnit = new TextView(ctx); hUnit.setText("时"); hUnit.setTextSize(10); hUnit.setTextColor(CLR_WHITE);
        timeRow.addView(hUnit);
        final EditText minEt = smallBorderedEdit(ctx, d, String.format("%02d", sMinuteCache), CLR_HIGHLIGHT);
        minEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        minEt.setLayoutParams(lpFixW(PX(d, 32)));
        minEt.setText(String.format("%02d", sMinuteCache));
        timeRow.addView(minEt);
        TextView miUnit = new TextView(ctx); miUnit.setText("分"); miUnit.setTextSize(10); miUnit.setTextColor(CLR_WHITE);
        timeRow.addView(miUnit);
        final EditText secEt = smallBorderedEdit(ctx, d, "00", CLR_HIGHLIGHT);
        secEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        secEt.setLayoutParams(lpFixW(PX(d, 32)));
        timeRow.addView(secEt);
        TextView sUnit = new TextView(ctx); sUnit.setText("秒"); sUnit.setTextSize(10); sUnit.setTextColor(CLR_WHITE);
        timeRow.addView(sUnit);

        View spT = new View(ctx); spT.setLayoutParams(lpWeight(1)); timeRow.addView(spT);
        card.addView(timeRow);
        card.addView(hSep(ctx, d));

        LinearLayout repeatRow = new LinearLayout(ctx);
        repeatRow.setOrientation(LinearLayout.HORIZONTAL);
        repeatRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rpLabel = label(ctx, d, "重复规则");
        rpLabel.setLayoutParams(lpFixW(PX(d, 72)));
        repeatRow.addView(rpLabel);
        View rsp1 = new View(ctx); rsp1.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 4), 0)); repeatRow.addView(rsp1);

        final Spinner repeatSp = new Spinner(ctx);
        repeatSp.setAdapter(new ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, REPEAT_MODES) {
            @Override public View getView(int pos, View v, ViewGroup p) { View tv = super.getView(pos, v, p); ((TextView)tv).setTextColor(CLR_HIGHLIGHT); ((TextView)tv).setTextSize(11); return tv; }
            @Override public View getDropDownView(int pos, View v, ViewGroup p) { View tv = super.getDropDownView(pos, v, p); ((TextView)tv).setTextColor(CLR_WHITE); ((TextView)tv).setBackgroundColor(CLR_CARD); return tv; }
        });
        for (int i = 0; i < REPEAT_MODES.length; i++) { if (REPEAT_MODES[i].equals(sRepeatCache)) { repeatSp.setSelection(i); break; } }
        repeatRow.addView(repeatSp);
        View spR = new View(ctx); spR.setLayoutParams(lpWeight(1)); repeatRow.addView(spR);
        card.addView(repeatRow);
        card.addView(hSep(ctx, d));

        TextView enableTg = cardToggle(ctx, d, "已启用", "未启用", false);
        card.addView(rowLabel(ctx, d, "任务状态", enableTg));

        card.setTag(new Object[]{nameEt, yearEt, monEt, dayEt, hourEt, minEt, secEt, repeatSp, enableTg});
        sNameEt = nameEt; sYearEt = yearEt; sMonEt = monEt; sDayEt = dayEt; sHourEt = hourEt; sMinEt = minEt; sSecEt = secEt;
        return card;
    }

    // ===== 卡片2: 消息内容配置 =====

    private static View buildCard2(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "");

        TextView typeTitle = new TextView(ctx);
        typeTitle.setText("选择消息类型");
        typeTitle.setTextSize(11);
        typeTitle.setTextColor(CLR_WHITE);
        typeTitle.setTypeface(null, Typeface.BOLD);
        typeTitle.setPadding(0, 0, 0, PX(d, 6));
        card.addView(typeTitle);

        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout[] rows = new LinearLayout[3];
        for (int r = 0; r < 3; r++) {
            rows[r] = new LinearLayout(ctx);
            rows[r].setOrientation(LinearLayout.HORIZONTAL);
            rows[r].setPadding(0, 0, 0, r < 2 ? PX(d, 4) : 0);
            for (int c = 0; c < 3; c++) {
                final int idx = r * 3 + c;
                TextView btn = messageTypeButton(ctx, d, MSG_TYPES[idx], idx == sSelectedMsgType);
                btn.setLayoutParams(lpWeight(1));
                btn.setOnClickListener(v -> {
                    sSelectedMsgType = idx;
                    refreshMsgTypeGrid(rows);
                    refreshTypeContent(ctx, d, act);
                    checkXmlWarning(ctx, idx);
                });
                rows[r].addView(btn);
            }
            grid.addView(rows[r]);
        }
        card.addView(grid);
        card.addView(hSep(ctx, d));

        sTypeContentContainer = new LinearLayout(ctx);
        sTypeContentContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(sTypeContentContainer);
        refreshTypeContent(ctx, d, act);

        card.setTag(new Object[]{rows});
        return card;
    }

    private static void refreshTypeContent(Context ctx, float d, Activity act) {
        if (sTypeContentContainer == null) return;
        sTypeContentContainer.removeAllViews();
        int scW = ctx.getResources().getDisplayMetrics().widthPixels - PX(d, 20);

        switch (sSelectedMsgType) {
            case 0: // 文本消息
                buildBigEditContent(ctx, d, scW, false);
                break;
            case 1: // 图片消息
                buildImageMsgContent(ctx, d, act);
                break;
            case 2: // 视频消息
                buildFilePickContent(ctx, d, act, "选取视频文件", "video/*");
                break;
            case 3: // 图文消息
                buildBigEditContent(ctx, d, scW, true);
                break;
            case 4: // 语音消息
                buildFilePickContent(ctx, d, act, "选取音频文件(.mp3/.wav)", "audio/*");
                break;
            case 5: // 位置消息
                buildLocationPickContent(ctx, d, act);
                break;
            case 6: // 艾特公告
                buildBigEditContent(ctx, d, scW, false);
                break;
            case 7: // 名片消息
                buildContactPickContent(ctx, d, act);
                break;
            case 8: // 文件消息
                buildFilePickContent(ctx, d, act, "选取文件", "*/*");
                break;
        }

        TextView previewBtn = new TextView(ctx);
        previewBtn.setText("预览消息");
        previewBtn.setTextSize(11);
        previewBtn.setTextColor(CLR_NEON);
        previewBtn.setPadding(PX(d, 14), PX(d, 6), PX(d, 14), PX(d, 6));
        GradientDrawable prevBg = new GradientDrawable();
        prevBg.setCornerRadius(PX(d, 2));
        prevBg.setStroke(PX(d, 1), CLR_NEON);
        prevBg.setColor(Color.TRANSPARENT);
        previewBtn.setBackground(prevBg);
        previewBtn.setOnClickListener(v -> showPreview(ctx, d, sContentCache, sSelectedMsgType));

        LinearLayout btnWrap = new LinearLayout(ctx);
        btnWrap.setOrientation(LinearLayout.HORIZONTAL);
        btnWrap.setGravity(Gravity.CENTER);
        btnWrap.setPadding(0, PX(d, 8), 0, 0);
        btnWrap.addView(previewBtn);
        sTypeContentContainer.addView(btnWrap);
    }

    private static void buildBigEditContent(Context ctx, float d, int scW, boolean withImageBtn) {
        final EditText et = new EditText(ctx);
        et.setHint("输入消息内容...");
        et.setHintTextColor(CLR_GRAY);
        et.setTextColor(CLR_WHITE);
        et.setTextSize(12);
        et.setSingleLine(false);
        et.setMaxLines(6);
        et.setPadding(PX(d, 10), PX(d, 10), PX(d, 10), PX(d, 10));
        et.setGravity(Gravity.TOP | Gravity.START);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(PX(d, 2));
        etBg.setStroke(PX(d, 1), CLR_NEON);
        etBg.setColor(Color.TRANSPARENT);
        et.setBackground(etBg);
        et.setText(sContentCache);
        et.setLayoutParams(new LinearLayout.LayoutParams(scW, PX(d, 120)));
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable e) { sContentCache = e.toString(); }
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
        });
        sTypeContentContainer.addView(et);

        LinearLayout varRow = new LinearLayout(ctx);
        varRow.setOrientation(LinearLayout.HORIZONTAL);
        varRow.setGravity(Gravity.CENTER);
        varRow.setPadding(0, PX(d, 6), 0, 0);
        String[] vars = {"{昵称}","{群名称}","{当前时间}"};
        int[] varColors = {CLR_HIGHLIGHT, CLR_YELLOW, CLR_NEON};
        for (int i = 0; i < vars.length; i++) {
            final String var = vars[i];
            TextView vb = varBtn(ctx, d, var, varColors[i]);
            vb.setOnClickListener(v -> {
                int sel = Math.max(et.getSelectionStart(), 0);
                et.getText().insert(sel, var);
            });
            varRow.addView(vb);
            if (i < vars.length - 1) { View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 6), 0)); varRow.addView(sp); }
        }
        View spV = new View(ctx); spV.setLayoutParams(lpWeight(1)); varRow.addView(spV);
        sTypeContentContainer.addView(varRow);

        TextView hint = new TextView(ctx);
        hint.setText("点击按钮即可自动填入变量，多条文案用 | 分隔随机发送");
        hint.setTextSize(9);
        hint.setTextColor(CLR_GRAY);
        hint.setPadding(0, PX(d, 4), 0, 0);
        sTypeContentContainer.addView(hint);

        if (withImageBtn) {
            Activity act = sActRef != null ? sActRef.get() : null;
            if (act != null) {
                TextView imgBtn = textBtn(ctx, d, "点击选择手机图片", CLR_NEON);
                imgBtn.setPadding(PX(d, 16), PX(d, 8), PX(d, 16), PX(d, 8));
                imgBtn.setOnClickListener(v -> {
                    sPickedFiles.clear();
                    sOnFilePicked = () -> {
                        sOnFilePicked = null;
                        Activity a = sActRef != null ? sActRef.get() : null;
                        if (a != null) a.runOnUiThread(() -> refreshTypeContent(ctx, d, a));
                    };
                    startImagePicker(act);
                });
                LinearLayout wrap = new LinearLayout(ctx);
                wrap.setOrientation(LinearLayout.HORIZONTAL);
                wrap.setGravity(Gravity.CENTER);
                wrap.setPadding(0, PX(d, 6), 0, 0);
                wrap.addView(imgBtn);
                sTypeContentContainer.addView(wrap);

                if (!sPickedFiles.isEmpty()) {
                    for (String path : sPickedFiles) {
                        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                        if (name.length() > 30) name = name.substring(0, 30) + "...";
                        TextView picked = new TextView(ctx);
                        picked.setText("已选: " + name);
                        picked.setTextSize(10);
                        picked.setTextColor(CLR_HIGHLIGHT);
                        picked.setPadding(0, PX(d, 4), 0, 0);
                        sTypeContentContainer.addView(picked);
                    }
                }
            }
        }
    }

    private static TextView varBtn(Context ctx, float d, String text, int color) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(10);
        btn.setTextColor(color);
        btn.setPadding(PX(d, 8), PX(d, 4), PX(d, 8), PX(d, 4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 2));
        bg.setStroke(PX(d, 1), color);
        bg.setColor(Color.TRANSPARENT);
        btn.setBackground(bg);
        return btn;
    }

    // 图片消息 = 两个按钮
    private static void buildImageMsgContent(Context ctx, float d, Activity act) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        TextView albumBtn = textBtn(ctx, d, "相册选择", CLR_NEON);
        albumBtn.setOnClickListener(v -> startImagePicker(act));
        albumBtn.setLayoutParams(lpWeight(1)); row.addView(albumBtn);
        View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 6), 0)); row.addView(sp);
        TextView fileBtn = textBtn(ctx, d, "文件选取", CLR_WHITE);
        fileBtn.setOnClickListener(v -> startFilePicker(act, "image/*"));
        fileBtn.setLayoutParams(lpWeight(1)); row.addView(fileBtn);
        sTypeContentContainer.addView(row);

        showPickedFile(ctx, d);
    }

    // 文件选取类型：视频/语音/文件
    private static void buildFilePickContent(Context ctx, float d, Activity act, String btnText, String mime) {
        sOnFilePicked = () -> {
            sOnFilePicked = null;
            Activity a = sActRef != null ? sActRef.get() : null;
            if (a != null) a.runOnUiThread(() -> refreshTypeContent(ctx, d, a));
        };
        sPickedFiles.clear();

        TextView btn = textBtn(ctx, d, btnText, CLR_NEON);
        btn.setOnClickListener(v -> {
            sPickedFiles.clear();
            sOnFilePicked = () -> {
                sOnFilePicked = null;
                Activity a = sActRef != null ? sActRef.get() : null;
                if (a != null) a.runOnUiThread(() -> refreshTypeContent(ctx, d, a));
            };
            if (mime.startsWith("video")) startFilePicker(act, mime);
            else if (mime.startsWith("audio")) startFilePicker(act, mime);
            else startFilePicker(act, mime);
        });
        sTypeContentContainer.addView(btn);
        showPickedFile(ctx, d);
    }

    private static void showPickedFile(Context ctx, float d) {
        for (String path : sPickedFiles) {
            String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            if (name.length() > 30) name = name.substring(0, 30) + "...";
            TextView picked = new TextView(ctx);
            picked.setText("已选: " + name);
            picked.setTextSize(10);
            picked.setTextColor(CLR_HIGHLIGHT);
            picked.setPadding(0, PX(d, 4), 0, 0);
            sTypeContentContainer.addView(picked);
        }
    }

    // 位置消息
    private static void buildLocationPickContent(Context ctx, float d, Activity act) {
        TextView btn = textBtn(ctx, d, "点击选择位置", CLR_NEON);
        btn.setOnClickListener(v -> {
            Toast.makeText(ctx, "请先打开微信内置位置发送页面获取坐标\n后续版本将集成地图选点", Toast.LENGTH_LONG).show();
        });
        sTypeContentContainer.addView(btn);

        TextView hint = new TextView(ctx);
        hint.setText("暂请手动输入经纬度到文本消息发送");
        hint.setTextSize(9); hint.setTextColor(CLR_GRAY);
        hint.setPadding(0, PX(d, 4), 0, 0);
        sTypeContentContainer.addView(hint);
    }

    // 名片消息
    private static void buildContactPickContent(Context ctx, float d, Activity act) {
        TextView btn = textBtn(ctx, d, "选择联系人", CLR_NEON);
        btn.setOnClickListener(v -> {
            ContactPickerDialog.show(act, "", 0, (wxids, display) -> {
                if (!wxids.isEmpty()) {
                    sContentCache = wxids.iterator().next();
                    Activity a = sActRef != null ? sActRef.get() : null;
                    if (a != null) a.runOnUiThread(() -> refreshTypeContent(ctx, d, a));
                }
            });
        });
        sTypeContentContainer.addView(btn);

        if (sContentCache != null && !sContentCache.isEmpty()) {
            TextView picked = new TextView(ctx);
            picked.setText("已选: " + sContentCache);
            picked.setTextSize(10);
            picked.setTextColor(CLR_HIGHLIGHT);
            picked.setPadding(0, PX(d, 4), 0, 0);
            sTypeContentContainer.addView(picked);
        }
    }

    private static void refreshMsgTypeGrid(LinearLayout[] rows) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                final int idx = r * 3 + c;
                View child = rows[r].getChildAt(c);
                if (child instanceof TextView) {
                    boolean sel = (idx == sSelectedMsgType);
                    ((TextView) child).setTextColor(sel ? Color.BLACK : CLR_NEON);
                    ((TextView) child).setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(PX(dp(child.getContext()), 2));
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
        btn.setTextSize(10);
        btn.setTextColor(selected ? Color.BLACK : CLR_NEON);
        btn.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(PX(d, 4), PX(d, 6), PX(d, 4), PX(d, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 2));
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
        cbg.setCornerRadius(PX(d, 2));
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
        upBg.setCornerRadius(PX(d, 2));
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
        previewArea.setOrientation(LinearLayout.VERTICAL);
        previewArea.setPadding(0, PX(d, 8), 0, 0);
        card.addView(previewArea);

        uploadBtn.setOnClickListener(v -> {
            sOnFilePicked = () -> {
                sOnFilePicked = null;
                for (String p : sPickedFiles) {
                    if (!sMaterialFiles.contains(p)) sMaterialFiles.add(p);
                }
                sPickedFiles.clear();
                if (sActRef != null) {
                    Activity a = sActRef.get();
                    if (a != null) a.runOnUiThread(() -> refreshMaterialPreviews(ctx, d, previewArea, countLabel));
                }
            };
            startMaterialPicker(act);
        });
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
            empty.setText("暂无素材，点击上方按钮上传");
            empty.setTextSize(11); empty.setTextColor(CLR_GRAY);
            empty.setPadding(0, PX(d, 4), 0, 0);
            area.addView(empty);
            countLabel.setText("0 个素材");
            return;
        }

        // Separate images, audio, and others
        List<String> images = new ArrayList<>();
        List<String> audios = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String p : sMaterialFiles) {
            String l = p.toLowerCase();
            if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".bmp")) {
                images.add(p);
            } else if (l.endsWith(".mp3") || l.endsWith(".wav") || l.endsWith(".ogg") || l.endsWith(".aac") || l.endsWith(".m4a") || l.endsWith(".flac") || l.endsWith(".amr") || l.endsWith(".wma")) {
                audios.add(p);
            } else {
                others.add(p);
            }
        }

        // Image grid: 3 per row
        int COLS = 3;
        int thumbW = PX(d, 72);
        int thumbH = PX(d, 72);
        int gap = PX(d, 4);
        for (int row = 0; row * COLS < images.size(); row++) {
            LinearLayout imgRow = new LinearLayout(ctx);
            imgRow.setOrientation(LinearLayout.HORIZONTAL);
            imgRow.setPadding(0, 0, 0, gap);
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col;
                if (idx >= images.size()) break;
                final String path = images.get(idx);
                final int fi = idx;

                LinearLayout cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(PX(d, 1), PX(d, 1), PX(d, 1), PX(d, 1));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                if (col < COLS - 1) clp.setMargins(0, 0, gap, 0);
                cell.setLayoutParams(clp);

                // Thumbnail
                ImageView thumb = new ImageView(ctx);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                try {
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inSampleSize = 4;
                    Bitmap bm = BitmapFactory.decodeFile(path, opt);
                    if (bm != null) thumb.setImageBitmap(bm);
                } catch (Exception ignored) {}
                GradientDrawable thumbBg = new GradientDrawable();
                thumbBg.setCornerRadius(PX(d, 2));
                thumbBg.setStroke(PX(d, 1), 0x33336655);
                thumbBg.setColor(CLR_CARD);
                thumb.setBackground(thumbBg);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(thumbW, thumbH);
                cell.addView(thumb, tlp);

                // File name below
                String name = new java.io.File(path).getName();
                if (name.length() > 10) name = name.substring(0, 9) + "…";
                TextView fname = new TextView(ctx);
                fname.setText(name);
                fname.setTextSize(9); fname.setTextColor(CLR_GRAY);
                fname.setMaxWidth(thumbW);
                fname.setPadding(0, PX(d, 2), 0, 0);
                cell.addView(fname);

                // Click to remove
                cell.setOnClickListener(v -> {
                    sMaterialFiles.remove(fi);
                    refreshMaterialPreviews(ctx, d, area, countLabel);
                });
                cell.setOnLongClickListener(v -> {
                    Toast.makeText(ctx, path, Toast.LENGTH_SHORT).show();
                    return true;
                });
                imgRow.addView(cell);
            }
            area.addView(imgRow);
        }

        // Audio items: filename + play/pause button
        for (int i = 0; i < audios.size(); i++) {
            final String path = audios.get(i);
            final int ai = images.size() + i;

            LinearLayout audioItem = new LinearLayout(ctx);
            audioItem.setOrientation(LinearLayout.HORIZONTAL);
            audioItem.setGravity(Gravity.CENTER_VERTICAL);
            audioItem.setPadding(PX(d, 8), PX(d, 4), PX(d, 8), PX(d, 4));
            GradientDrawable aibg = new GradientDrawable();
            aibg.setCornerRadius(PX(d, 2));
            aibg.setStroke(PX(d, 1), 0x33336655);
            aibg.setColor(CLR_CARD);
            audioItem.setBackground(aibg);
            LinearLayout.LayoutParams ailp = new LinearLayout.LayoutParams(-1, -2);
            ailp.setMargins(0, 0, 0, gap);
            audioItem.setLayoutParams(ailp);

            TextView audioIcon = new TextView(ctx);
            audioIcon.setText("♪");
            audioIcon.setTextSize(16); audioIcon.setTextColor(CLR_NEON);
            audioItem.addView(audioIcon);

            String aname = new java.io.File(path).getName();
            if (aname.length() > 24) aname = aname.substring(0, 23) + "…";
            TextView aText = new TextView(ctx);
            aText.setText(aname);
            aText.setTextSize(10); aText.setTextColor(CLR_WHITE);
            aText.setPadding(PX(d, 6), 0, PX(d, 6), 0);
            LinearLayout.LayoutParams atlp = new LinearLayout.LayoutParams(0, -2, 1);
            audioItem.addView(aText, atlp);

            TextView playBtn = new TextView(ctx);
            playBtn.setText("▶ 播放");
            playBtn.setTextSize(10);
            playBtn.setTextColor(CLR_YELLOW);
            playBtn.setPadding(PX(d, 8), PX(d, 3), PX(d, 8), PX(d, 3));
            GradientDrawable pbg = new GradientDrawable();
            pbg.setCornerRadius(PX(d, 2));
            pbg.setStroke(PX(d, 1), CLR_YELLOW);
            pbg.setColor(Color.TRANSPARENT);
            playBtn.setBackground(pbg);
            playBtn.setTag(Boolean.FALSE); // isPlaying flag
            playBtn.setOnClickListener(v -> toggleAudioPlayback(ctx, path, playBtn));
            audioItem.addView(playBtn);

            audioItem.setOnLongClickListener(v -> {
                sMaterialFiles.remove(ai);
                refreshMaterialPreviews(ctx, d, area, countLabel);
                return true;
            });
            area.addView(audioItem);
        }

        // Other file types: simple icon + name row
        for (int i = 0; i < others.size(); i++) {
            final String path = others.get(i);
            final int oi = images.size() + audios.size() + i;

            LinearLayout otherItem = new LinearLayout(ctx);
            otherItem.setOrientation(LinearLayout.HORIZONTAL);
            otherItem.setGravity(Gravity.CENTER_VERTICAL);
            otherItem.setPadding(PX(d, 8), PX(d, 4), PX(d, 8), PX(d, 4));
            GradientDrawable oibg = new GradientDrawable();
            oibg.setCornerRadius(PX(d, 2));
            oibg.setStroke(PX(d, 1), 0x33336655);
            oibg.setColor(CLR_CARD);
            otherItem.setBackground(oibg);
            LinearLayout.LayoutParams oilp = new LinearLayout.LayoutParams(-1, -2);
            oilp.setMargins(0, 0, 0, gap);
            otherItem.setLayoutParams(oilp);

            TextView oicon = new TextView(ctx);
            oicon.setText(fileIcon(path));
            oicon.setTextSize(14); oicon.setTextColor(CLR_HIGHLIGHT);
            otherItem.addView(oicon);

            String oname = new java.io.File(path).getName();
            if (oname.length() > 28) oname = oname.substring(0, 27) + "…";
            TextView oText = new TextView(ctx);
            oText.setText(oname);
            oText.setTextSize(10); oText.setTextColor(CLR_WHITE);
            oText.setPadding(PX(d, 6), 0, PX(d, 6), 0);
            LinearLayout.LayoutParams otlp = new LinearLayout.LayoutParams(0, -2, 1);
            otherItem.addView(oText, otlp);

            otherItem.setOnClickListener(v -> {
                sMaterialFiles.remove(oi);
                refreshMaterialPreviews(ctx, d, area, countLabel);
            });
            otherItem.setOnLongClickListener(v -> {
                Toast.makeText(ctx, path, Toast.LENGTH_SHORT).show();
                return true;
            });
            area.addView(otherItem);
        }

        countLabel.setText(sMaterialFiles.size() + " 个素材");
    }

    private static android.media.MediaPlayer sMediaPlayer = null;
    private static String sCurrentAudioPath = null;

    private static void toggleAudioPlayback(Context ctx, String path, TextView btn) {
        boolean playing = Boolean.TRUE.equals(btn.getTag());
        if (playing) {
            if (sMediaPlayer != null) {
                sMediaPlayer.stop();
                sMediaPlayer.release();
                sMediaPlayer = null;
            }
            sCurrentAudioPath = null;
            btn.setText("▶ 播放");
            btn.setTextColor(CLR_YELLOW);
            GradientDrawable bg = (GradientDrawable) btn.getBackground();
            bg.setStroke(PX(ctx.getResources().getDisplayMetrics().density, 1), CLR_YELLOW);
            btn.setTag(Boolean.FALSE);
        } else {
            if (sMediaPlayer != null) {
                sMediaPlayer.stop();
                sMediaPlayer.release();
                sMediaPlayer = null;
            }
            try {
                sMediaPlayer = new android.media.MediaPlayer();
                sMediaPlayer.setDataSource(path);
                sMediaPlayer.prepare();
                sMediaPlayer.start();
                sMediaPlayer.setOnCompletionListener(mp -> {
                    mp.release();
                    sMediaPlayer = null;
                    sCurrentAudioPath = null;
                    btn.post(() -> {
                        btn.setText("▶ 播放");
                        btn.setTextColor(CLR_YELLOW);
                        GradientDrawable bbg = (GradientDrawable) btn.getBackground();
                        bbg.setStroke(PX(ctx.getResources().getDisplayMetrics().density, 1), CLR_YELLOW);
                        btn.setTag(Boolean.FALSE);
                    });
                });
                sCurrentAudioPath = path;
                btn.setText("⏸ 暂停");
                btn.setTextColor(CLR_NEON);
                GradientDrawable nbg = (GradientDrawable) btn.getBackground();
                nbg.setStroke(PX(ctx.getResources().getDisplayMetrics().density, 1), CLR_NEON);
                btn.setTag(Boolean.TRUE);
            } catch (Exception e) {
                Toast.makeText(ctx, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
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
            cbg.setCornerRadius(PX(d, 2));
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
                    g.setCornerRadius(PX(d, 2));
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
        selRow.setGravity(Gravity.CENTER);

        TextView selBtn = new TextView(ctx);
        selBtn.setText("选择联系人");
        selBtn.setTextSize(10);
        selBtn.setTextColor(CLR_NEON);
        selBtn.setPadding(PX(d, 8), PX(d, 6), PX(d, 8), PX(d, 6));
        GradientDrawable selBg = new GradientDrawable();
        selBg.setCornerRadius(PX(d, 2));
        selBg.setStroke(PX(d, 1), CLR_NEON);
        selBg.setColor(Color.TRANSPARENT);
        selBtn.setBackground(selBg);
        selRow.addView(selBtn);

        final TextView countTv = new TextView(ctx);
        countTv.setText(sSelectedContacts.size() + "人");
        countTv.setTextSize(10);
        countTv.setTextColor(CLR_HIGHLIGHT);
        countTv.setPadding(PX(d, 6), 0, 0, 0);
        selRow.addView(countTv);

        View spMid = new View(ctx); spMid.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 14), 0)); selRow.addView(spMid);

        TextView excBtn = new TextView(ctx);
        excBtn.setText("排除名单");
        excBtn.setTextSize(10);
        excBtn.setTextColor(CLR_RED);
        excBtn.setPadding(PX(d, 8), PX(d, 6), PX(d, 8), PX(d, 6));
        GradientDrawable excBg = new GradientDrawable();
        excBg.setCornerRadius(PX(d, 2));
        excBg.setStroke(PX(d, 1), CLR_RED);
        excBg.setColor(Color.TRANSPARENT);
        excBtn.setBackground(excBg);
        selRow.addView(excBtn);

        final TextView excCountTv = new TextView(ctx);
        excCountTv.setText(sExcludeContacts.size() + "人");
        excCountTv.setTextSize(10);
        excCountTv.setTextColor(CLR_RED);
        excCountTv.setPadding(PX(d, 6), 0, 0, 0);
        selRow.addView(excCountTv);

        card.addView(selRow);

        selBtn.setOnClickListener(v -> {
            ContactPickerDialog.show(act, joinSet(",", sSelectedContacts),
                sSelectedChannel == 1 ? 1 : 0,
                (wxids, display) -> {
                    sSelectedContacts.clear();
                    sSelectedContacts.addAll(wxids);
                    countTv.setText(sSelectedContacts.size() + "人");
                });
        });

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

        TextView autoDeleteTg = cardToggle(ctx, d, "已开启", "已关闭", false);
        momentsExtra.addView(rowLabel(ctx, d, "24h自动删除", autoDeleteTg));

        card.addView(momentsExtra);
        card.setTag(new Object[]{countTv, excCountTv, momentsExtra, visibleEt, locationEt, commentEt, autoDeleteTg});

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

        LinearLayout intRow = new LinearLayout(ctx);
        intRow.setOrientation(LinearLayout.HORIZONTAL);
        intRow.setGravity(Gravity.CENTER_VERTICAL);
        intRow.setPadding(0, PX(d, 3), 0, PX(d, 3));

        TextView intLabel = new TextView(ctx);
        intLabel.setText("发送时间间隔");
        intLabel.setTextSize(11); intLabel.setTextColor(CLR_WHITE);
        intLabel.setPadding(0, 0, PX(d, 6), 0);
        intRow.addView(intLabel);

        final EditText intervalEt = smallBorderedEdit(ctx, d, "5", CLR_HIGHLIGHT);
        intervalEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalEt.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 52), -2));
        intRow.addView(intervalEt);

        TextView intUnit = new TextView(ctx);
        intUnit.setText(" 秒");
        intUnit.setTextSize(11); intUnit.setTextColor(CLR_WHITE);
        intRow.addView(intUnit);

        View spInt = new View(ctx); spInt.setLayoutParams(lpWeight(1)); intRow.addView(spInt);

        card.addView(intRow);
        card.addView(hSep(ctx, d));

        LinearLayout ranRow = new LinearLayout(ctx);
        ranRow.setOrientation(LinearLayout.HORIZONTAL);
        ranRow.setGravity(Gravity.CENTER_VERTICAL);
        ranRow.setPadding(0, PX(d, 3), 0, PX(d, 3));
        TextView ranLabel = new TextView(ctx);
        ranLabel.setText("随机浮动延迟");
        ranLabel.setTextSize(11); ranLabel.setTextColor(CLR_WHITE);
        ranRow.addView(ranLabel);
        View spRan = new View(ctx); spRan.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 10), 0)); ranRow.addView(spRan);
        TextView randomTg = cardToggle(ctx, d, "ON", "OFF", false);
        ranRow.addView(randomTg);
        View spR2 = new View(ctx); spR2.setLayoutParams(lpWeight(1)); ranRow.addView(spR2);
        card.addView(ranRow);
        card.addView(hSep(ctx, d));

        LinearLayout batchRow = new LinearLayout(ctx);
        batchRow.setOrientation(LinearLayout.HORIZONTAL);
        batchRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView bsLabel = new TextView(ctx);
        bsLabel.setText("每次发送");
        bsLabel.setTextSize(11); bsLabel.setTextColor(CLR_WHITE);
        batchRow.addView(bsLabel);

        final EditText batchSizeEt = smallBorderedEdit(ctx, d, "10", CLR_HIGHLIGHT);
        batchSizeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        batchSizeEt.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 52), -2));
        batchRow.addView(batchSizeEt);

        TextView bsUnit = new TextView(ctx);
        bsUnit.setText(" 个  ");
        bsUnit.setTextSize(11); bsUnit.setTextColor(CLR_WHITE);
        batchRow.addView(bsUnit);

        View bsp1 = new View(ctx); bsp1.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 8), 0)); batchRow.addView(bsp1);

        TextView biLabel = new TextView(ctx);
        biLabel.setText("批次间隔");
        biLabel.setTextSize(11); biLabel.setTextColor(CLR_WHITE);
        batchRow.addView(biLabel);

        final EditText batchIntEt = smallBorderedEdit(ctx, d, "1", CLR_HIGHLIGHT);
        batchIntEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        batchIntEt.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 52), -2));
        batchRow.addView(batchIntEt);

        TextView biUnit = new TextView(ctx);
        biUnit.setText(" 分");
        biUnit.setTextSize(11); biUnit.setTextColor(CLR_WHITE);
        batchRow.addView(biUnit);

        View spB = new View(ctx); spB.setLayoutParams(lpWeight(1)); batchRow.addView(spB);

        card.addView(batchRow);
        card.addView(hSep(ctx, d));

        LinearLayout maxRow = new LinearLayout(ctx);
        maxRow.setOrientation(LinearLayout.HORIZONTAL);
        maxRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView msLabel = new TextView(ctx);
        msLabel.setText("单次任务最大发送");
        msLabel.setTextSize(11); msLabel.setTextColor(CLR_WHITE);
        maxRow.addView(msLabel);

        final EditText maxSendEt = smallBorderedEdit(ctx, d, "200", CLR_HIGHLIGHT);
        maxSendEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxSendEt.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 52), -2));
        maxRow.addView(maxSendEt);

        TextView msUnit = new TextView(ctx);
        msUnit.setText(" 个目标");
        msUnit.setTextSize(11); msUnit.setTextColor(CLR_WHITE);
        maxRow.addView(msUnit);

        View spM = new View(ctx); spM.setLayoutParams(lpWeight(1)); maxRow.addView(spM);

        card.addView(maxRow);

        card.setTag(new Object[]{intervalEt, randomTg, batchSizeEt, batchIntEt, maxSendEt});
        return card;
    }

    // ===== 卡片6: 高级策略设置 =====

    private static View buildCard6(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "高级策略设置");

        LinearLayout retryRow = new LinearLayout(ctx);
        retryRow.setOrientation(LinearLayout.HORIZONTAL);
        retryRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView rtLabel = new TextView(ctx);
        rtLabel.setText("最大重试次数(次)");
        rtLabel.setTextSize(11); rtLabel.setTextColor(CLR_WHITE);
        retryRow.addView(rtLabel);

        final EditText retryTimesEt = smallBorderedEdit(ctx, d, "3", CLR_HIGHLIGHT);
        retryTimesEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        retryTimesEt.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 52), -2));
        retryRow.addView(retryTimesEt);

        TextView spR = new TextView(ctx);
        spR.setText("   重试等待");
        spR.setTextSize(11); spR.setTextColor(CLR_WHITE);
        retryRow.addView(spR);

        View rsp1 = new View(ctx); rsp1.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 6), 0)); retryRow.addView(rsp1);

        final EditText retryIntEt = smallBorderedEdit(ctx, d, "60", CLR_HIGHLIGHT);
        retryIntEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        retryIntEt.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 52), -2));
        retryRow.addView(retryIntEt);

        TextView riUnit = new TextView(ctx);
        riUnit.setText(" 秒");
        riUnit.setTextSize(11); riUnit.setTextColor(CLR_WHITE);
        retryRow.addView(riUnit);

        View sp = new View(ctx); sp.setLayoutParams(lpWeight(1)); retryRow.addView(sp);

        card.addView(retryRow);

        TextView hint = new TextView(ctx);
        hint.setText("多次重试失败将自动标记该对象跳过，不阻断整体群发队列");
        hint.setTextSize(10); hint.setTextColor(CLR_GRAY);
        hint.setPadding(0, PX(d, 6), 0, 0);
        card.addView(hint);

        card.setTag(new Object[]{retryTimesEt, retryIntEt});
        return card;
    }

    // ===== 卡片7: 模板与历史日志 =====

    private static View buildCard7(Context ctx, float d, Activity act) {
        LinearLayout card = makeCard(ctx, d, "模板与历史日志");

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        TextView saveTplBtn = textBtn(ctx, d, "保存为模板", CLR_NEON);
        btnRow.addView(saveTplBtn);

        View sp1 = new View(ctx); sp1.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 6), 0)); btnRow.addView(sp1);

        TextView loadTplBtn = textBtn(ctx, d, "加载模板", CLR_WHITE);
        btnRow.addView(loadTplBtn);

        View sp2 = new View(ctx); sp2.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 6), 0)); btnRow.addView(sp2);

        TextView logBtn = textBtn(ctx, d, "发送历史", CLR_YELLOW);
        btnRow.addView(logBtn);

        card.addView(btnRow);
        card.addView(hSep(ctx, d));

        final TextView logCount = new TextView(ctx);
        List<SendLogEntry> logs = ScheduleBroadcast.getSendLogs();
        List<SendLogEntry> failed = ScheduleBroadcast.getFailedLogs();
        logCount.setText("共" + logs.size() + "条  失败" + failed.size() + "条");
        logCount.setTextSize(11);
        logCount.setTextColor(CLR_HIGHLIGHT);
        logCount.setGravity(Gravity.CENTER);
        card.addView(logCount);

        saveTplBtn.setOnClickListener(v -> showSaveTemplate(ctx, d, act));
        loadTplBtn.setOnClickListener(v -> showLoadTemplate(ctx, d, act));
        logBtn.setOnClickListener(v -> showSendLogs(ctx, d, act));

        card.setTag(logCount);
        return card;
    }

    private static TextView textBtn(Context ctx, float d, String text, int color) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(10);
        btn.setTextColor(color);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(PX(d, 10), PX(d, 6), PX(d, 10), PX(d, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 2));
        bg.setStroke(PX(d, 1), color);
        bg.setColor(Color.TRANSPARENT);
        btn.setBackground(bg);
        return btn;
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
            ttype.setText(MSG_TYPES[Math.min(sSelectedMsgType, 8)] + " | " + CHANNELS[Math.max(0, Math.min(2, tpl.channel))]);
            ttype.setTextSize(10); ttype.setTextColor(CLR_GRAY);
            col.addView(ttype);

            row.addView(col);

            TextView loadBtn = new TextView(ctx);
            loadBtn.setText("加载");
            loadBtn.setTextSize(11); loadBtn.setTextColor(CLR_NEON);
            loadBtn.setPadding(PX(d, 10), PX(d, 6), PX(d, 10), PX(d, 6));
            GradientDrawable ldBg = new GradientDrawable();
            ldBg.setCornerRadius(PX(d, 2));
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

        // 持久化保存
        android.content.SharedPreferences sp = ctx.getSharedPreferences("leshao_send_logs", Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        String FS = "\u0001"; String RS = "\u0002";
        for (SendLogEntry e : logs) {
            if (sb.length() > 0) sb.append(RS);
            sb.append(e.success ? "1" : "0").append(FS)
              .append(e.timestamp).append(FS)
              .append(nullToEmpty(e.targetWxid)).append(FS)
              .append(nullToEmpty(e.targetName)).append(FS)
              .append(nullToEmpty(e.taskName)).append(FS)
              .append(nullToEmpty(e.content)).append(FS)
              .append(nullToEmpty(e.error));
        }
        if (sb.length() > 0) sp.edit().putString("logs_persist", sb.toString()).apply();

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PX(d, 16), PX(d, 16), PX(d, 16), PX(d, 16));
        root.setBackgroundColor(CLR_BG);

        TextView title = new TextView(ctx);
        title.setText("发送历史"); title.setTextSize(14); title.setTextColor(CLR_WHITE);
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
            rsBg.setCornerRadius(PX(d, 2));
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
            w.setLayout(ctx.getResources().getDisplayMetrics().widthPixels - (int)(16 * d), -2);
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
        btnBg.setCornerRadius(PX(d, 2));
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
            boolean isFileType = (sSelectedMsgType == 1 || sSelectedMsgType == 2 || sSelectedMsgType == 4 || sSelectedMsgType == 8);
            if (!isFileType && content.isEmpty()) { Toast.makeText(ctx, "请输入消息内容", Toast.LENGTH_SHORT).show(); return; }
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

            // 素材文件路径 - sPickedFiles优先(send msg), sMaterialFiles备用(upload)
            if (!sPickedFiles.isEmpty()) {
                task.filePath = sPickedFiles.get(sPickedFiles.size() - 1);
            } else if (!sMaterialFiles.isEmpty()) {
                task.filePath = sMaterialFiles.get(0);
            }

            // 语言设置
            task.varNickname = content.contains("{昵称}");
            task.varGroupName = content.contains("{群名称}");
            task.varTime = content.contains("{当前时间}");
            task.varDate = content.contains("{date}");

            // 计算触发时间
            Calendar cal = Calendar.getInstance();
            int year = parseInt(getTextOrHint(sYearEt), sYearCache);
            int month = parseInt(getTextOrHint(sMonEt), sMonthCache) - 1;
            int day = parseInt(getTextOrHint(sDayEt), sDayCache);
            int hour = parseInt(getTextOrHint(sHourEt), sHourCache);
            int minute = parseInt(getTextOrHint(sMinEt), sMinuteCache);
            int second = parseInt(getTextOrHint(sSecEt), 0);
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, day);
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, second);
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
        card.setPadding(PX(d, 10), PX(d, 10), PX(d, 10), PX(d, 10));
        card.setClipToOutline(true);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(PX(d, 2));
        cardBg.setStroke(PX(d, 1), CLR_NEON);
        cardBg.setColor(CLR_CARD);
        card.setBackground(cardBg);

        if (title != null && !title.isEmpty()) {
            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView iv = new TextView(ctx);
            iv.setTextColor(CLR_NEON);
            iv.setTextSize(10);
            iv.setPadding(0, 0, PX(d, 6), 0);
            String icon = title.contains("任务基础") ? "\u2139" : title.contains("消息内容") ? "\u2709" :
                title.contains("素材文件") ? "\uD83D\uDCC1" : title.contains("发送目标") ? "\uD83C\uDFAF" :
                title.contains("风控") ? "\u26A1" : title.contains("高级") ? "\u2699" : "\uD83D\uDCCB";
            iv.setText(icon);
            header.addView(iv);

            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(13);
            t.setTextColor(CLR_WHITE);
            t.setTypeface(null, Typeface.BOLD);
            t.setLayoutParams(lpWeight(1));
            header.addView(t);

            card.addView(header);

            View div = new View(ctx);
            div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            div.setBackgroundColor(0x22336655);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 1);
            dlp.setMargins(0, PX(d, 6), 0, PX(d, 8));
            div.setLayoutParams(dlp);
            card.addView(div);
        }

        return card;
    }

    private static LinearLayout rowLabel(Context ctx, float d, String labelText, View widget) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, PX(d, 3), 0, PX(d, 3));

        TextView tv = label(ctx, d, labelText);
        tv.setLayoutParams(lpFixW(PX(d, 72)));
        row.addView(tv);

        View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 4), 0)); row.addView(sp);

        row.addView(widget);
        return row;
    }

    private static TextView label(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(CLR_WHITE);
        tv.setPadding(0, 0, PX(d, 6), 0);
        return tv;
    }

    private static EditText editText(Context ctx, float d, String hint, int textColor) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(CLR_GRAY);
        et.setTextColor(textColor);
        et.setBackgroundColor(Color.TRANSPARENT);
        et.setPadding(PX(d, 8), PX(d, 4), PX(d, 8), PX(d, 4));
        et.setTextSize(11);
        et.setSingleLine(false);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        return et;
    }

    private static EditText borderedEditText(Context ctx, float d, String hint, int textColor) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(CLR_GRAY);
        et.setTextColor(textColor);
        et.setPadding(PX(d, 10), PX(d, 6), PX(d, 10), PX(d, 6));
        et.setTextSize(11);
        et.setSingleLine(true);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(PX(d, 2));
        etBg.setStroke(PX(d, 1), CLR_NEON);
        etBg.setColor(Color.TRANSPARENT);
        et.setBackground(etBg);
        return et;
    }

    private static TextView cardToggle(Context ctx, float d, String onText, String offText, boolean initial) {
        final boolean[] state = {initial};
        final int[] clr = {initial ? Color.BLACK : CLR_NEON};
        TextView tv = new TextView(ctx);
        tv.setText((initial ? " " : "") + (initial ? offText : onText));
        tv.setTextSize(10);
        tv.setTextColor(clr[0]);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(PX(d, 8), PX(d, 6), PX(d, 8), PX(d, 6));
        GradientDrawable tg = new GradientDrawable();
        tg.setCornerRadius(PX(d, 2));
        if (initial) { tg.setColor(CLR_NEON); } else { tg.setStroke(PX(d, 1), CLR_NEON); tg.setColor(Color.TRANSPARENT); }
        tv.setBackground(tg);
        tv.setOnClickListener(v -> {
            state[0] = !state[0];
            clr[0] = state[0] ? Color.BLACK : CLR_NEON;
            tv.setTextColor(clr[0]);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(PX(d, 2));
            if (state[0]) { g.setColor(CLR_NEON); } else { g.setStroke(PX(d, 1), CLR_NEON); g.setColor(Color.TRANSPARENT); }
            tv.setBackground(g);
            tv.setText((state[0] ? " " : "") + (state[0] ? offText : onText));
            tv.setTag(state[0]);
        });
        tv.setTag(state[0]);
        return tv;
    }

    private static boolean isCardToggled(View cardToggle) { Object t = cardToggle.getTag(); return t instanceof Boolean && (Boolean) t; }

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
    private static String nullToEmpty(String s) { return s != null ? s : ""; }
    private static int PX(float d, int dp) { return (int)(dp * d); }
    private static float dp(Context ctx) { return ctx.getResources().getDisplayMetrics().density; }
    private static String nvl(String s) { return s == null ? "" : s; }
    private static int parseInt(String s, int def) { if (s == null || s.isEmpty()) return def; try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; } }
    private static String getTextOrHint(EditText et) { if (et == null) return ""; String t = et.getText().toString().trim(); return t.isEmpty() ? et.getHint().toString() : t; }

    private static LinearLayout rowLabelW(Context ctx, float d, String labelText, View widget, int labelW) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, PX(d, 3), 0, PX(d, 3));
        TextView tv = label(ctx, d, labelText);
        tv.setLayoutParams(lpFixW(PX(d, labelW)));
        row.addView(tv);
        View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(PX(d, 4), 0)); row.addView(sp);
        row.addView(widget);
        return row;
    }

    private static EditText smallBorderedEdit(Context ctx, float d, String hint, int textColor) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(CLR_GRAY);
        et.setTextColor(textColor);
        et.setPadding(PX(d, 6), PX(d, 3), PX(d, 6), PX(d, 3));
        et.setTextSize(10);
        et.setSingleLine(true);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(PX(d, 2));
        etBg.setStroke(PX(d, 1), CLR_NEON);
        etBg.setColor(Color.TRANSPARENT);
        et.setBackground(etBg);
        return et;
    }

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
