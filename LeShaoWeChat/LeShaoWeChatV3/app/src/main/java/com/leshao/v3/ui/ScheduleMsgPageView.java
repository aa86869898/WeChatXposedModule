package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import com.leshao.v3.hook.ScheduleBroadcast;
import com.leshao.v3.hook.ScheduleBroadcast.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ScheduleMsgPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = dp(ctx);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding(PX(d, 14), PX(d, 14), PX(d, 14), PX(d, 40));

        // ============================================================
        // 1. 全局控制区
        // ============================================================
        root.addView(buildBanner(ctx, d));

        // ============================================================
        // 2. 消息配置
        // ============================================================
        LinearLayout msgCard = card(ctx, d, 0x1F4DD, "消息配置");
        final Spinner typeSpinner = spinner(ctx, d,
            "文本消息", "图片消息", "语音消息", "视频消息", "文件消息", "微信名片", "链接/小程序", "@所有人公告");
        msgCard.addView(typeSpinner);

        final EditText contentEdit = editText(ctx, d, "输入发送内容...\n支持模板变量: {date} {weekday} {time} {group_name} {member_count} {nickname}");
        contentEdit.setMinLines(4);
        msgCard.addView(contentEdit);

        // 模板快捷按钮
        LinearLayout tplRow = new LinearLayout(ctx);
        tplRow.setOrientation(LinearLayout.HORIZONTAL);
        for (Map.Entry<String, String> e : ScheduleBroadcast.TEMPLATE_LIBRARY.entrySet()) {
            tplRow.addView(tagBtn(ctx, d, e.getKey(), () -> contentEdit.append(e.getValue())));
        }
        msgCard.addView(tplRow);

        // 变量和选项勾选
        LinearLayout varRow = new LinearLayout(ctx);
        varRow.setOrientation(LinearLayout.HORIZONTAL);
        final CheckBox cbNickname = check(ctx, "昵称");
        final CheckBox cbDate = check(ctx, "日期");
        final CheckBox cbTime = check(ctx, "时间");
        final CheckBox cbGroup = check(ctx, "群名");
        final CheckBox cbMember = check(ctx, "人数");
        final CheckBox cbEmoji = check(ctx, "随机表情");
        varRow.addView(cbNickname); varRow.addView(cbDate); varRow.addView(cbTime);
        varRow.addView(cbGroup); varRow.addView(cbMember); varRow.addView(cbEmoji);
        msgCard.addView(varRow);

        // 内容池、随机文案
        LinearLayout poolRow = new LinearLayout(ctx);
        poolRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText poolEdit = editText(ctx, d, "随机文案池（用 | 分隔多条文案）");
        poolEdit.setLayoutParams(lp(0, -2, 1));
        poolRow.addView(poolEdit);
        msgCard.addView(poolRow);

        root.addView(msgCard);
        root.addView(space(ctx, d));

        // ============================================================
        // 3. 定时设置
        // ============================================================
        LinearLayout timeCard = card(ctx, d, 0x1F552, "定时设置");

        LinearLayout timeInputRow = new LinearLayout(ctx);
        timeInputRow.setOrientation(LinearLayout.HORIZONTAL);

        final EditText hourEt = miniInput(ctx, d, "时");
        final EditText minEt = miniInput(ctx, d, "分");
        final EditText dateEt = miniInput(ctx, d, "MM-dd");

        timeInputRow.addView(label(ctx, d, "时间", 0.20f));
        timeInputRow.addView(hourEt); timeInputRow.addView(text(ctx, ":"));
        timeInputRow.addView(minEt); timeInputRow.addView(label(ctx, d, "日期", 0.30f));
        timeInputRow.addView(dateEt);
        timeCard.addView(timeInputRow);

        final Spinner repeatSp = spinner(ctx, d, "一次性", "每天循环", "每周循环", "自定义间隔(秒)");
        timeCard.addView(wrap(ctx, d, "重复模式", repeatSp));

        final EditText intervalEt = editText(ctx, d, "自定义间隔（秒），如 3600 = 每小时");
        intervalEt.setVisibility(View.GONE);
        timeCard.addView(intervalEt);

        repeatSp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                intervalEt.setVisibility(pos == 3 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        root.addView(timeCard);
        root.addView(space(ctx, d));

        // ============================================================
        // 4. 群组选择
        // ============================================================
        LinearLayout groupCard = card(ctx, d, 0x1F465, "群组选择");

        final CheckBox allGroupCb = check(ctx, "发送到全部群聊");
        allGroupCb.setChecked(true);
        groupCard.addView(allGroupCb);

        final CheckBox randCb = check(ctx, "群组随机顺序发送");
        groupCard.addView(randCb);

        final EditText groupEt = editText(ctx, d, "指定群 wxid（逗号分隔多个）\n留空 = 全部群");
        groupCard.addView(groupEt);

        final EditText excludeEt = editText(ctx, d, "排除群 wxid（逗号分隔，黑名单）");
        groupCard.addView(excludeEt);

        allGroupCb.setOnCheckedChangeListener((b, v) -> {
            groupEt.setEnabled(!v);
            groupEt.setAlpha(v ? 0.5f : 1.0f);
        });

        root.addView(groupCard);
        root.addView(space(ctx, d));

        // ============================================================
        // 5. 文件素材
        // ============================================================
        LinearLayout fileCard = card(ctx, d, 0x1F4C1, "文件素材");

        final EditText fileEt = editText(ctx, d, "文件路径（图片/音频/视频/文档的本地绝对路径）");
        fileCard.addView(fileEt);

        final TextView fileHint = new TextView(ctx);
        fileHint.setText("提示：请先将文件放入手机存储，然后填入完整路径\n如 /sdcard/Download/image.jpg");
        fileHint.setTextSize(10);
        fileHint.setTextColor(AppColors.text2());
        fileHint.setPadding(0, PX(d, 4), 0, 0);
        fileCard.addView(fileHint);

        root.addView(fileCard);
        root.addView(space(ctx, d));

        // ============================================================
        // 6. 发送策略
        // ============================================================
        LinearLayout strategyCard = card(ctx, d, 0x2699, "发送策略");

        final Spinner intervalSp = spinner(ctx, d, "5秒", "10秒", "15秒", "30秒", "60秒", "120秒");
        strategyCard.addView(wrap(ctx, d, "群间最小间隔", intervalSp));

        final Spinner retrySp = spinner(ctx, d, "不重试", "重试1次", "重试2次", "重试3次", "重试5次");
        strategyCard.addView(wrap(ctx, d, "失败重试次数", retrySp));

        final Spinner dailySp = spinner(ctx, d, "50", "100", "200", "500", "1000", "9999(不限制)");
        strategyCard.addView(wrap(ctx, d, "每日发送上限", dailySp));

        // 条件开关行
        LinearLayout condRow = new LinearLayout(ctx);
        condRow.setOrientation(LinearLayout.HORIZONTAL);
        final CheckBox wifiCb = check(ctx, "仅WiFi");
        final CheckBox chargeCb = check(ctx, "仅充电");
        final CheckBox nightCb = check(ctx, "夜间限速");
        condRow.addView(wifiCb); condRow.addView(chargeCb); condRow.addView(nightCb);
        strategyCard.addView(condRow);

        root.addView(strategyCard);
        root.addView(space(ctx, d));

        // ============================================================
        // 保存按钮
        // ============================================================
        LinearLayout saveRow = new LinearLayout(ctx);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.setGravity(Gravity.CENTER);

        Button saveTaskBtn = bigBtn(ctx, d, "保存定时任务", AppColors.accent());
        saveTaskBtn.setOnClickListener(v -> {
            saveTask(ctx, d, typeSpinner, contentEdit, cbNickname, cbDate, cbTime, cbGroup, cbMember,
                    cbEmoji, poolEdit, hourEt, minEt, dateEt, repeatSp, intervalEt,
                    allGroupCb, randCb, groupEt, excludeEt, fileEt,
                    intervalSp, retrySp, dailySp, wifiCb, chargeCb, nightCb);
            refreshTaskList(ctx, d);
        });
        saveRow.addView(saveTaskBtn, lp(0, -2, 1));

        View svg = new View(ctx); svg.setLayoutParams(lp(PX(d, 10), 0)); saveRow.addView(svg);

        Button saveDraftBtn = bigBtn(ctx, d, "保存为草稿", 0xFF607D8B);
        saveDraftBtn.setOnClickListener(v -> {
            saveDraft(ctx, d, contentEdit);
            refreshDraftList(ctx, d);
        });
        saveRow.addView(saveDraftBtn, lp(0, -2, 1));

        root.addView(saveRow);
        root.addView(space(ctx, d));

        // ============================================================
        // 7. 任务列表 & 草稿箱 & 日志
        // ============================================================
        taskContainer = new LinearLayout(ctx);
        taskContainer.setOrientation(LinearLayout.VERTICAL);
        draftContainer = new LinearLayout(ctx);
        draftContainer.setOrientation(LinearLayout.VERTICAL);

        // 标签栏
        LinearLayout contentTabs = new LinearLayout(ctx);
        contentTabs.setOrientation(LinearLayout.HORIZONTAL);
        contentTabs.setBackgroundColor(AppColors.whiteCard());
        GradientDrawable tabsBg = new GradientDrawable();
        tabsBg.setCornerRadius(PX(d, 10));
        tabsBg.setColor(AppColors.whiteCard());
        contentTabs.setBackground(tabsBg);
        contentTabs.setPadding(PX(d, 14), PX(d, 12), PX(d, 14), PX(d, 12));

        Button[] tabBtns = new Button[3];
        final View[] tabViews = {taskContainer, draftContainer, new LinearLayout(ctx)};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            String[] labels = {"任务列表", "草稿箱", "运行日志"};
            tabBtns[i] = tabButton(ctx, d, labels[i], i == 0);
            tabBtns[i].setOnClickListener(v -> {
                for (int j = 0; j < 3; j++) {
                    tabBtns[j].setTextColor(j == idx ? Color.WHITE : AppColors.text1());
                    GradientDrawable g = (GradientDrawable) tabBtns[j].getBackground();
                    g.setColor(j == idx ? AppColors.accent() : Color.TRANSPARENT);
                    tabViews[j].setVisibility(j == idx ? View.VISIBLE : View.GONE);
                }
            });
            contentTabs.addView(tabBtns[i]);
            View sp = new View(ctx); sp.setLayoutParams(lp(PX(d, 8), 0)); contentTabs.addView(sp);
        }
        root.addView(contentTabs);

        root.addView(taskContainer);
        root.addView(draftContainer);
        draftContainer.setVisibility(View.GONE);
        root.addView(tabViews[2]);
        tabViews[2].setVisibility(View.GONE);

        // 初始化日志面板
        refreshLogPanel(ctx, d, (LinearLayout) tabViews[2]);
        refreshTaskList(ctx, d);
        refreshDraftList(ctx, d);

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);
        return sv;
    }

    // ================================================================
    // 保存逻辑
    // ================================================================

    static LinearLayout taskContainer, draftContainer;
    static int[] TYPE_CODES = {1, 3, 34, 43, 47, 42, 49, 1};
    static long[] REPEAT_MS = {0, 86400000L, 604800000L, -1};
    static String[] INTERVAL_VALS = {"5","10","15","30","60","120"};
    static int[] RETRY_VALS = {0, 1, 2, 3, 5};
    static String[] DAILY_VALS = {"50","100","200","500","1000","9999"};

    private static void saveTask(Context ctx, float d, Spinner typeSp, EditText contentEt,
            CheckBox cbNick, CheckBox cbDate, CheckBox cbTime, CheckBox cbGroup, CheckBox cbMember,
            CheckBox cbEmoji, EditText poolEt, EditText hourEt, EditText minEt, EditText dateEt,
            Spinner repeatSp, EditText intervalEt, CheckBox allGroupCb, CheckBox randCb,
            EditText groupEt, EditText excludeEt, EditText fileEt,
            Spinner intervalSp, Spinner retrySp, Spinner dailySp,
            CheckBox wifiCb, CheckBox chargeCb, CheckBox nightCb) {
        try {
            Task t = new Task();
            t.msgType = TYPE_CODES[typeSp.getSelectedItemPosition()];
            t.content = contentEt.getText().toString().trim();
            t.varNickname = cbNick.isChecked();
            t.varDate = cbDate.isChecked();
            t.varTime = cbTime.isChecked();
            t.varGroupName = cbGroup.isChecked();
            t.varMemberCount = cbMember.isChecked();
            t.randomEmoji = cbEmoji.isChecked();

            String pool = poolEt.getText().toString().trim();
            if (!pool.isEmpty()) t.contentPool = new ArrayList<>(Arrays.asList(pool.split("\\s*\\|\\s*")));

            t.sendAllGroups = allGroupCb.isChecked();
            t.randomOrder = randCb.isChecked();

            String wxids = groupEt.getText().toString().trim();
            if (!wxids.isEmpty()) for (String w : wxids.split(",")) {
                String tr = w.trim(); if (!tr.isEmpty()) t.targetGroups.add(tr);
            }

            String file = fileEt.getText().toString().trim();
            if (!file.isEmpty()) t.filePath = file;

            // 时间
            String h = hourEt.getText().toString().trim();
            String m = minEt.getText().toString().trim();
            int hour = h.isEmpty() ? 8 : Integer.parseInt(h);
            int min = m.isEmpty() ? 0 : Integer.parseInt(m);
            Calendar cal = Calendar.getInstance();
            String ds = dateEt.getText().toString().trim();
            if (!ds.isEmpty()) {
                String[] ps = ds.split("-");
                cal.set(Calendar.MONTH, Integer.parseInt(ps[0]) - 1);
                cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(ps[1]));
            }
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, min);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            t.triggerTime = cal.getTimeInMillis();
            if (t.triggerTime <= System.currentTimeMillis()) t.triggerTime += 86400000;

            // 重复
            int rp = repeatSp.getSelectedItemPosition();
            if (rp == 3) {
                String iv = intervalEt.getText().toString().trim();
                t.repeatInterval = iv.isEmpty() ? 3600 : Integer.parseInt(iv) * 1000L;
            } else t.repeatInterval = REPEAT_MS[rp];

            // 策略
            int ival = Integer.parseInt(INTERVAL_VALS[intervalSp.getSelectedItemPosition()]);
            ScheduleBroadcast.setMinInterval(ival);
            ScheduleBroadcast.setDailyMax(Integer.parseInt(DAILY_VALS[dailySp.getSelectedItemPosition()].replaceAll("[^0-9]", "")));
            ScheduleBroadcast.setWifiOnly(wifiCb.isChecked());
            ScheduleBroadcast.setChargingOnly(chargeCb.isChecked());
            ScheduleBroadcast.setNightSilent(nightCb.isChecked());

            // 排除群
            String ex = excludeEt.getText().toString().trim();
            if (!ex.isEmpty()) {
                Set<String> exSet = new HashSet<>();
                for (String w : ex.split(",")) { String tr = w.trim(); if (!tr.isEmpty()) exSet.add(tr); }
                // Use reflection for setExcludeGroups since it's package-private
                try { ScheduleBroadcast.class.getMethod("setExcludeGroups", Set.class).invoke(null, exSet); } catch (Throwable ignored) {}
            }

            ScheduleBroadcast.addTask(t);
            clearForm(contentEt, poolEt, hourEt, minEt, dateEt, groupEt, fileEt, intervalEt);
            toast(ctx, "定时任务已创建");
        } catch (Throwable ex) {
            toast(ctx, "数据格式有误，请检查输入");
        }
    }

    private static void saveDraft(Context ctx, float d, EditText contentEt) {
        String text = contentEt.getText().toString().trim();
        if (text.isEmpty()) { toast(ctx, "请输入消息内容"); return; }
        Task t = new Task();
        t.content = text;
        ScheduleBroadcast.saveDraft(t);
        contentEt.setText("");
        toast(ctx, "草稿已保存");
    }

    private static void clearForm(EditText... fields) {
        for (EditText f : fields) f.setText("");
    }

    // ================================================================
    // UI 组件工厂
    // ================================================================

    private static View buildBanner(Context ctx, float d) {
        LinearLayout banner = new LinearLayout(ctx);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(PX(d, 16), PX(d, 16), PX(d, 16), PX(d, 16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF667eea, 0xFF764ba2});
        bg.setCornerRadius(PX(d, 14));
        banner.setBackground(bg);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(lp(0, -2, 1));

        TextView title = new TextView(ctx);
        title.setText("定时消息群发");
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        col.addView(title);

        TextView status = new TextView(ctx);
        boolean running = ScheduleBroadcast.isRunning();
        int tasks = ScheduleBroadcast.getTaskCount(), today = ScheduleBroadcast.getDailyCount();
        status.setText((running ? "运行中" : "已停止") + " | " + tasks + "任务 | 今日" + today + " | "
                + ScheduleBroadcast.getGroupCount() + "群");
        status.setTextSize(11);
        status.setTextColor(0xCCFFFFFF);
        status.setPadding(0, PX(d, 4), 0, 0);
        col.addView(status);

        banner.addView(col);

        Switch sw = new Switch(ctx);
        sw.setChecked(ScheduleBroadcast.isEnabled());
        sw.setOnCheckedChangeListener((b, v) -> ScheduleBroadcast.setEnabled(v));
        banner.addView(sw);

        return banner;
    }

    private static LinearLayout card(Context ctx, float d, int icon, String title) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(PX(d, 14), PX(d, 14), PX(d, 14), PX(d, 14));
        card.setBackgroundColor(AppColors.whiteCard());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 12));
        bg.setColor(AppColors.whiteCard());
        card.setBackground(bg);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView ic = new TextView(ctx);
        ic.setText(new String(Character.toChars(icon)));
        ic.setTextSize(16);
        header.addView(ic);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(AppColors.text1());
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(PX(d, 8), 0, 0, 0);
        header.addView(t);

        card.addView(header);

        View div = new View(ctx);
        div.setLayoutParams(lp(-1, 1));
        div.setBackgroundColor(AppColors.divider());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 1);
        dlp.setMargins(0, PX(d, 8), 0, PX(d, 10));
        div.setLayoutParams(dlp);
        card.addView(div);

        return card;
    }

    private static View wrap(Context ctx, float d, String label, View child) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, PX(d, 6), 0, PX(d, 2));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setTextColor(AppColors.text1());
        row.addView(tv, lp(0, -2, 0.35f));

        child.setLayoutParams(lp(0, -2, 0.65f));
        row.addView(child);
        return row;
    }

    private static Spinner spinner(Context ctx, float d, String... items) {
        Spinner sp = new Spinner(ctx);
        sp.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, items));
        sp.setPadding(0, 0, 0, 0);
        return sp;
    }

    private static EditText editText(Context ctx, float d, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setTextColor(AppColors.text1());
        et.setHintTextColor(AppColors.text2());
        et.setBackgroundColor(AppColors.bg());
        et.setPadding(PX(d, 12), PX(d, 10), PX(d, 12), PX(d, 10));
        et.setTextSize(13);
        et.setSingleLine(false);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(PX(d, 8));
        etBg.setColor(AppColors.bg());
        et.setBackground(etBg);
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(-1, -2);
        etlp.setMargins(0, PX(d, 6), 0, 0);
        et.setLayoutParams(etlp);
        return et;
    }

    private static EditText miniInput(Context ctx, float d, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setTextColor(AppColors.text1());
        et.setHintTextColor(AppColors.text2());
        et.setBackgroundColor(AppColors.bg());
        et.setPadding(PX(d, 8), PX(d, 8), PX(d, 8), PX(d, 8));
        et.setTextSize(12);
        et.setSingleLine(true);
        et.setGravity(Gravity.CENTER);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(PX(d, 6));
        etBg.setColor(AppColors.bg());
        et.setBackground(etBg);
        et.setLayoutParams(lp(PX(d, 48), -2));
        return et;
    }

    private static TextView label(Context ctx, float d, String text, float weight) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(PX(d, 8), 0, PX(d, 4), 0);
        tv.setLayoutParams(lp(0, -2, weight));
        return tv;
    }

    private static TextView text(Context ctx, String s) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.text1());
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private static Button tagBtn(Context ctx, float d, String text, Runnable onClick) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(10);
        b.setTextColor(Color.WHITE);
        b.setPadding(PX(d, 8), PX(d, 4), PX(d, 8), PX(d, 4));
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 12));
        bg.setColor(0xFF8E44AD);
        b.setBackground(bg);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMargins(0, PX(d, 6), PX(d, 6), 0);
        b.setLayoutParams(blp);
        return b;
    }

    private static CheckBox check(Context ctx, String text) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(text);
        cb.setTextSize(11);
        cb.setTextColor(AppColors.text1());
        return cb;
    }

    private static Button bigBtn(Context ctx, float d, String text, int color) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setTypeface(null, Typeface.BOLD);
        b.setPadding(PX(d, 16), PX(d, 12), PX(d, 16), PX(d, 12));
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 24));
        bg.setColor(color);
        b.setBackground(bg);
        return b;
    }

    private static Button tabButton(Context ctx, float d, String text, boolean active) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(active ? Color.WHITE : AppColors.text1());
        b.setPadding(PX(d, 12), PX(d, 6), PX(d, 12), PX(d, 6));
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 16));
        bg.setColor(active ? AppColors.accent() : Color.TRANSPARENT);
        b.setBackground(bg);
        return b;
    }

    private static View space(Context ctx, float d) {
        View v = new View(ctx);
        v.setLayoutParams(lp(-1, PX(d, 12)));
        return v;
    }

    // ================================================================
    // 列表刷新
    // ================================================================

    private static void refreshTaskList(Context ctx, float d) {
        if (taskContainer == null) return;
        taskContainer.removeAllViews();
        List<Task> tasks = ScheduleBroadcast.getAllTasks();

        if (tasks.isEmpty()) {
            taskContainer.addView(emptyText(ctx, d, "暂无定时任务，请先创建"));
            return;
        }

        for (Task t : tasks) {
            taskContainer.addView(taskItem(ctx, d, t));
        }
    }

    private static View taskItem(Context ctx, float d, Task t) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(PX(d, 14), PX(d, 12), PX(d, 14), PX(d, 12));
        item.setBackgroundColor(AppColors.whiteCard());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 10));
        bg.setColor(AppColors.whiteCard());
        item.setBackground(bg);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.setMargins(0, 0, 0, PX(d, 8));
        item.setLayoutParams(ilp);

        // Top: type + time
        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        int color = t.enabled ? 0xFF27AE60 : 0xFF95A5A6;
        TextView typeTv = new TextView(ctx);
        typeTv.setText((t.enabled ? "" : "[暂停] ") + typeName(t.msgType) + " · " + repeatLabel(t.repeatInterval));
        typeTv.setTextSize(12);
        typeTv.setTextColor(color);
        typeTv.setTypeface(null, Typeface.BOLD);
        typeTv.setLayoutParams(lp(0, -2, 1));
        topRow.addView(typeTv);

        TextView timeTv = new TextView(ctx);
        timeTv.setText(new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(t.triggerTime)));
        timeTv.setTextSize(11);
        timeTv.setTextColor(AppColors.text2());
        topRow.addView(timeTv);
        item.addView(topRow);

        // Content
        if (t.content != null && !t.content.isEmpty()) {
            TextView contentTv = new TextView(ctx);
            contentTv.setText(t.content.length() > 60 ? t.content.substring(0, 60) + "…" : t.content);
            contentTv.setTextSize(11);
            contentTv.setTextColor(AppColors.text2());
            contentTv.setPadding(0, PX(d, 4), 0, 0);
            item.addView(contentTv);
        }

        // File path
        if (t.filePath != null && !t.filePath.isEmpty()) {
            TextView fileTv = new TextView(ctx);
            fileTv.setText("文件: " + t.filePath);
            fileTv.setTextSize(10);
            fileTv.setTextColor(0xFF3498DB);
            fileTv.setPadding(0, PX(d, 2), 0, 0);
            item.addView(fileTv);
        }

        // Bottom: stats + actions
        LinearLayout botRow = new LinearLayout(ctx);
        botRow.setOrientation(LinearLayout.HORIZONTAL);
        botRow.setGravity(Gravity.CENTER_VERTICAL);
        botRow.setPadding(0, PX(d, 8), 0, 0);

        String meta = "群:" + (t.sendAllGroups ? "全部" : t.targetGroups.size())
                + " | 已发:" + t.totalSendCount + " | 失败:" + t.failCount;
        if (t.randomOrder) meta += " | 随机";
        if (t.randomEmoji) meta += " | 表情";
        TextView metaTv = new TextView(ctx);
        metaTv.setText(meta);
        metaTv.setTextSize(10);
        metaTv.setTextColor(AppColors.text2());
        metaTv.setLayoutParams(lp(0, -2, 1));
        botRow.addView(metaTv);

        // 按键
        botRow.addView(miniButton(ctx, d, "启用", 0xFF27AE60, () -> {
            ScheduleBroadcast.enableTask(t.id, true);
            refreshTaskList(ctx, d);
        }));
        botRow.addView(miniButton(ctx, d, "禁用", 0xFF95A5A6, () -> {
            ScheduleBroadcast.enableTask(t.id, false);
            refreshTaskList(ctx, d);
        }));
        botRow.addView(miniButton(ctx, d, "删除", 0xFFE74C3C, () -> {
            ScheduleBroadcast.removeTask(t.id);
            refreshTaskList(ctx, d);
            toast(ctx, "已删除");
        }));

        item.addView(botRow);
        return item;
    }

    private static Button miniButton(Context ctx, float d, String text, int color, Runnable onClick) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(9);
        b.setTextColor(Color.WHITE);
        b.setPadding(PX(d, 8), PX(d, 3), PX(d, 8), PX(d, 3));
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 8));
        bg.setColor(color);
        b.setBackground(bg);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMargins(PX(d, 4), 0, 0, 0);
        b.setLayoutParams(blp);
        return b;
    }

    private static void refreshDraftList(Context ctx, float d) {
        if (draftContainer == null) return;
        draftContainer.removeAllViews();
        List<Task> drafts = ScheduleBroadcast.getDrafts();

        if (drafts.isEmpty()) {
            draftContainer.addView(emptyText(ctx, d, "草稿箱为空"));
            return;
        }

        for (Task t : drafts) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(PX(d, 14), PX(d, 10), PX(d, 14), PX(d, 10));
            row.setBackgroundColor(AppColors.whiteCard());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(PX(d, 8));
            bg.setColor(AppColors.whiteCard());
            row.setBackground(bg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, 0, 0, PX(d, 6));
            row.setLayoutParams(rlp);

            TextView tv = new TextView(ctx);
            String txt = t.content != null ? t.content : "(空)";
            tv.setText(txt.length() > 30 ? txt.substring(0, 30) + "…" : txt);
            tv.setTextSize(11);
            tv.setTextColor(AppColors.text1());
            tv.setLayoutParams(lp(0, -2, 1));
            row.addView(tv);

            row.addView(miniButton(ctx, d, "使用", 0xFF2980B9, () -> {
                t.triggerTime = System.currentTimeMillis() + 60000;
                t.enabled = true;
                ScheduleBroadcast.addTask(t);
                ScheduleBroadcast.removeDraft(t.id);
                refreshTaskList(ctx, d);
                refreshDraftList(ctx, d);
                toast(ctx, "草稿已转为任务");
            }));
            row.addView(miniButton(ctx, d, "删", 0xFFE74C3C, () -> {
                ScheduleBroadcast.removeDraft(t.id);
                refreshDraftList(ctx, d);
                toast(ctx, "已删除");
            }));
            draftContainer.addView(row);
        }
    }

    private static void refreshLogPanel(Context ctx, float d, LinearLayout panel) {
        panel.removeAllViews();
        panel.setPadding(PX(d, 14), PX(d, 14), PX(d, 14), PX(d, 14));
        panel.setBackgroundColor(AppColors.whiteCard());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(PX(d, 10));
        bg.setColor(AppColors.whiteCard());
        panel.setBackground(bg);

        boolean running = ScheduleBroadcast.isRunning();
        int tasks = ScheduleBroadcast.getTaskCount();
        int drafts = ScheduleBroadcast.getDraftCount();
        int groups = ScheduleBroadcast.getGroupCount();
        int today = ScheduleBroadcast.getDailyCount();
        int week = ScheduleBroadcast.getWeeklyCount();

        String[] lines = {
            "引擎状态: " + (running ? "运行中" : "已停止"),
            "全局开关: " + (ScheduleBroadcast.isEnabled() ? "已启用" : "已禁用"),
            "",
            "总任务数: " + tasks,
            "草稿数量: " + drafts,
            "群组数量: " + groups,
            "",
            "今日已发: " + today,
            "本周已发: " + week,
            "",
            "支持的消息类型: 文本/图片/语音/视频/文件/名片/链接/公告",
            "支持的循环模式: 一次性/每日/每周/自定义间隔",
        };

        for (String line : lines) {
            TextView tv = new TextView(ctx);
            tv.setText(line.isEmpty() ? " " : line);
            tv.setTextSize(11);
            tv.setTextColor(line.startsWith("引擎") ? 0xFF27AE60 :
                            line.startsWith("全局") ? (ScheduleBroadcast.isEnabled() ? 0xFF27AE60 : 0xFFE74C3C) :
                            AppColors.text2());
            tv.setPadding(0, PX(d, 2), 0, 0);
            panel.addView(tv);
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private static View emptyText(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(PX(d, 10), PX(d, 16), 0, 0);
        return tv;
    }

    private static String typeName(int t) {
        switch (t) { case 1: return "文本"; case 3: return "图片"; case 34: return "语音";
            case 43: return "视频"; case 47: return "表情"; case 42: return "名片"; case 49: return "链接"; default: return "消息"; }
    }

    private static String repeatLabel(long ms) {
        if (ms == 0) return "一次性"; if (ms == 86400000L) return "每日";
        if (ms == 604800000L) return "每周"; return (ms / 1000) + "秒";
    }

    private static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private static LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }
    private static int PX(float d, int dp) { return (int)(dp * d); }
    private static float dp(Context ctx) { return ctx.getResources().getDisplayMetrics().density; }
    private static void toast(Context ctx, String msg) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); }
}
