package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.leshao.v3.hook.ScheduleBroadcast;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class SchedulerPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(d, 16), dp(d, 16), dp(d, 16), dp(d, 16));

        final LinearLayout taskList = new LinearLayout(ctx);
        taskList.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout draftList = new LinearLayout(ctx);
        draftList.setOrientation(LinearLayout.VERTICAL);

        // ===== 统计状态栏 =====
        LinearLayout statusRow = new LinearLayout(ctx);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setPadding(dp(d, 8), dp(d, 4), dp(d, 8), dp(d, 12));
        statusRow.setBackgroundColor(AppColors.card());

        TextView status = new TextView(ctx);
        status.setTextSize(12);
        status.setTextColor(AppColors.text1());
        status.setText("任务:" + ScheduleBroadcast.getTaskCount() + " 草稿:" + ScheduleBroadcast.getDraftCount()
                + " 群:" + ScheduleBroadcast.getGroupCount() + " 今日:" + ScheduleBroadcast.getDailyCount());
        statusRow.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button stopBtn = smallBtn(ctx, d, "紧急停止", 0xFFE74C3C);
        stopBtn.setOnClickListener(v -> {
            ScheduleBroadcast.emergencyStop();
            Toast.makeText(ctx, "所有任务已停止", Toast.LENGTH_SHORT).show();
        });
        statusRow.addView(stopBtn);
        root.addView(statusRow);

        // ===== 新建任务 =====
        root.addView(sectionLabel(ctx, d, "新建任务"));

        Spinner typeSpinner = new Spinner(ctx);
        typeSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item,
                new String[]{"文本消息", "图片消息", "语音消息", "视频消息", "表情消息", "链接/AppMsg"}));
        root.addView(typeSpinner);

        EditText contentEdit = new EditText(ctx);
        contentEdit.setHint("发送内容");
        contentEdit.setTextColor(AppColors.text1());
        contentEdit.setHintTextColor(AppColors.text2());
        contentEdit.setMinLines(3);
        contentEdit.setPadding(dp(d, 12), dp(d, 10), dp(d, 12), dp(d, 10));
        contentEdit.setBackgroundColor(AppColors.card());
        root.addView(contentEdit);

        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setPadding(0, dp(d, 8), 0, 0);

        EditText hourEdit = new EditText(ctx); hourEdit.setHint("时");
        hourEdit.setTextColor(AppColors.text1()); hourEdit.setHintTextColor(AppColors.text2());
        hourEdit.setBackgroundColor(AppColors.card()); hourEdit.setPadding(dp(d, 10), dp(d, 8), dp(d, 10), dp(d, 8));
        hourEdit.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.25f));
        timeRow.addView(hourEdit);

        EditText minEdit = new EditText(ctx); minEdit.setHint("分");
        minEdit.setTextColor(AppColors.text1()); minEdit.setHintTextColor(AppColors.text2());
        minEdit.setBackgroundColor(AppColors.card()); minEdit.setPadding(dp(d, 10), dp(d, 8), dp(d, 10), dp(d, 8));
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.25f);
        minLp.setMargins(dp(d, 8), 0, 0, 0);
        minEdit.setLayoutParams(minLp);
        timeRow.addView(minEdit);

        EditText dateEdit = new EditText(ctx); dateEdit.setHint("日期 MM-dd");
        dateEdit.setTextColor(AppColors.text1()); dateEdit.setHintTextColor(AppColors.text2());
        dateEdit.setBackgroundColor(AppColors.card()); dateEdit.setPadding(dp(d, 10), dp(d, 8), dp(d, 10), dp(d, 8));
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f);
        dateLp.setMargins(dp(d, 8), 0, 0, 0);
        dateEdit.setLayoutParams(dateLp);
        timeRow.addView(dateEdit);
        root.addView(timeRow);

        EditText wxidEdit = new EditText(ctx);
        wxidEdit.setHint("目标群wxid(逗号分隔，留空=全部群)");
        wxidEdit.setTextColor(AppColors.text1()); wxidEdit.setHintTextColor(AppColors.text2());
        wxidEdit.setBackgroundColor(AppColors.card()); wxidEdit.setPadding(dp(d, 12), dp(d, 8), dp(d, 12), dp(d, 8));
        LinearLayout.LayoutParams wxidLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wxidLp.setMargins(0, dp(d, 8), 0, 0);
        wxidEdit.setLayoutParams(wxidLp);
        root.addView(wxidEdit);

        LinearLayout checkRow = new LinearLayout(ctx);
        checkRow.setOrientation(LinearLayout.HORIZONTAL);
        checkRow.setPadding(0, dp(d, 8), 0, 0);
        CheckBox allGroupCb = new CheckBox(ctx); allGroupCb.setText("全部群");
        allGroupCb.setChecked(true);
        CheckBox randCb = new CheckBox(ctx); randCb.setText("随机顺序");
        checkRow.addView(allGroupCb); checkRow.addView(randCb);
        root.addView(checkRow);

        // 文本选择快捷按钮行
        LinearLayout pickRow = new LinearLayout(ctx);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.setPadding(0, dp(d, 6), 0, 0);
        for (Map.Entry<String, String> e : ScheduleBroadcast.TEMPLATE_LIBRARY.entrySet()) {
            String key = e.getKey();
            Button b = new Button(ctx);
            b.setText(key);
            b.setTextSize(11);
            b.setPadding(dp(d, 8), dp(d, 4), dp(d, 8), dp(d, 4));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMargins(0, 0, dp(d, 4), 0);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> contentEdit.append(e.getValue()));
            pickRow.addView(b);
        }
        root.addView(pickRow);

        // 场景模板快速选择
        root.addView(sectionLabel(ctx, d, "场景模板"));
        LinearLayout sceneRow = new LinearLayout(ctx);
        sceneRow.setOrientation(LinearLayout.HORIZONTAL);
        sceneRow.setPadding(0, 0, 0, dp(d, 4));
        for (ScheduleBroadcast.SceneTemplate st : ScheduleBroadcast.SCENE_TEMPLATES) {
            Button b = new Button(ctx);
            b.setText(st.name.substring(0, Math.min(st.name.length(), 4)));
            b.setTextSize(11);
            b.setPadding(dp(d, 8), dp(d, 4), dp(d, 8), dp(d, 4));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMargins(0, 0, dp(d, 4), 0);
            b.setLayoutParams(blp);
            b.setOnClickListener(v -> {
                contentEdit.setText(st.content);
                hourEdit.setText(st.hour == -1 ? "" : String.valueOf(st.hour));
                minEdit.setText("0");
                typeSpinner.setSelection(0);
                allGroupCb.setChecked(true);
            });
            sceneRow.addView(b);
        }
        root.addView(sceneRow);

        // ===== 操作按钮行 =====
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(d, 12), 0, 0);

        Button addBtn = coloredBtn(ctx, d, "添加任务", AppColors.accent());
        addBtn.setOnClickListener(v -> {
            try {
                int[] types = {1, 3, 34, 43, 47, 49};
                int msgType = types[typeSpinner.getSelectedItemPosition()];

                ScheduleBroadcast.Task t = new ScheduleBroadcast.Task();
                t.msgType = msgType;
                t.content = contentEdit.getText().toString().trim();
                t.sendAllGroups = allGroupCb.isChecked();
                t.randomOrder = randCb.isChecked();

                String hourStr = hourEdit.getText().toString().trim();
                String minStr = minEdit.getText().toString().trim();
                int hour = hourStr.isEmpty() ? 0 : Integer.parseInt(hourStr);
                int min = minStr.isEmpty() ? 0 : Integer.parseInt(minStr);

                Calendar cal = Calendar.getInstance();
                String dateStr = dateEdit.getText().toString().trim();
                if (!dateStr.isEmpty()) {
                    String[] parts = dateStr.split("-");
                    cal.set(Calendar.MONTH, Integer.parseInt(parts[0]) - 1);
                    cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(parts[1]));
                }
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, min);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                t.triggerTime = cal.getTimeInMillis();
                if (t.triggerTime <= System.currentTimeMillis()) t.triggerTime += 86400000;

                String wxids = wxidEdit.getText().toString().trim();
                if (!wxids.isEmpty()) {
                    for (String w : wxids.split(",")) {
                        String trim = w.trim();
                        if (!trim.isEmpty()) t.targetGroups.add(trim);
                    }
                }

                if (t.sendAllGroups || !t.targetGroups.isEmpty()) {
                    ScheduleBroadcast.addTask(t);
                    contentEdit.setText(""); hourEdit.setText(""); minEdit.setText("");
                    dateEdit.setText(""); wxidEdit.setText("");
                    refreshTaskList(ctx, d, taskList);
                    Toast.makeText(ctx, "任务已添加", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, "请指定目标群或勾选全部群", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ex) {
                Toast.makeText(ctx, "输入格式错误", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(addBtn);

        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(d, 10), 0));
        btnRow.addView(spacer);

        Button draftBtn = coloredBtn(ctx, d, "保存草稿", 0xFF607D8B);
        draftBtn.setOnClickListener(v -> {
            try {
                int[] types = {1, 3, 34, 43, 47, 49};
                ScheduleBroadcast.Task t = new ScheduleBroadcast.Task();
                t.msgType = types[typeSpinner.getSelectedItemPosition()];
                t.content = contentEdit.getText().toString().trim();
                t.sendAllGroups = allGroupCb.isChecked();
                t.randomOrder = randCb.isChecked();
                String wxids = wxidEdit.getText().toString().trim();
                if (!wxids.isEmpty()) for (String w : wxids.split(",")) { String trim = w.trim(); if (!trim.isEmpty()) t.targetGroups.add(trim); }
                ScheduleBroadcast.saveDraft(t);
                contentEdit.setText(""); wxidEdit.setText("");
                refreshDraftList(ctx, d, draftList);
                Toast.makeText(ctx, "草稿已保存", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
        btnRow.addView(draftBtn);
        root.addView(btnRow);

        // ===== 指令说明 =====
        root.addView(sectionLabel(ctx, d, "文件助手指令"));
        LinearLayout cmdHelp = new LinearLayout(ctx);
        cmdHelp.setOrientation(LinearLayout.VERTICAL);
        cmdHelp.setBackgroundColor(AppColors.card());
        cmdHelp.setPadding(dp(d, 12), dp(d, 8), dp(d, 12), dp(d, 8));
        for (String cmd : new String[]{
                "##STOP - 紧急停止所有任务",
                "##STATUS - 查看运行状态",
                "##TASKS - 查看任务列表",
                "##RESET - 重置今日已发计数",
                "##TEMPLATES - 查看模板库",
                "##SCENES - 查看场景模板",
                "##GROUPS - 查看群列表"}) {
            TextView t = new TextView(ctx); t.setText(cmd); t.setTextSize(11); t.setTextColor(AppColors.text2());
            cmdHelp.addView(t);
        }
        root.addView(cmdHelp);

        // ===== 定时任务列表 =====
        LinearLayout taskHeader = new LinearLayout(ctx);
        taskHeader.setOrientation(LinearLayout.HORIZONTAL);
        taskHeader.setPadding(0, dp(d, 16), 0, dp(d, 4));

        TextView taskTitle = sectionLabel(ctx, d, "任务列表");
        taskTitle.setPadding(0, 0, 0, 0);
        taskHeader.addView(taskTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button refreshBtn = smallBtn(ctx, d, "刷新", 0xFF34495E);
        refreshBtn.setOnClickListener(v2 -> {
            refreshTaskList(ctx, d, taskList);
            refreshDraftList(ctx, d, draftList);
        });
        taskHeader.addView(refreshBtn);
        root.addView(taskHeader);

        root.addView(taskList);

        // ===== 草稿箱 =====
        root.addView(sectionLabel(ctx, d, "草稿箱"));
        root.addView(draftList);

        refreshTaskList(ctx, d, taskList);
        refreshDraftList(ctx, d, draftList);

        return root;
    }

    private static void refreshTaskList(Context ctx, float d, LinearLayout taskList) {
        taskList.removeAllViews();
        List<ScheduleBroadcast.Task> tasks = ScheduleBroadcast.getAllTasks();

        for (int i = 0; i < tasks.size(); i++) {
            final ScheduleBroadcast.Task t = tasks.get(i);

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(AppColors.card());
            card.setPadding(dp(d, 10), dp(d, 8), dp(d, 10), dp(d, 8));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, dp(d, 6));
            card.setLayoutParams(cardLp);

            String typeLabel;
            switch (t.msgType) {
                case 1: typeLabel = "文本"; break;
                case 3: typeLabel = "图片"; break;
                case 34: typeLabel = "语音"; break;
                case 43: typeLabel = "视频"; break;
                case 47: typeLabel = "表情"; break;
                case 49: typeLabel = "AppMsg"; break;
                default: typeLabel = "类型" + t.msgType;
            }
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            String timeStr = t.triggerTime > 0 ? sdf.format(new Date(t.triggerTime)) : "即时";
            String targetDesc = t.sendAllGroups ? "全部群" : (t.targetGroups.size() + "个群");
            String statusTag = t.enabled ? "[启用]" : "[暂停]";

            TextView info = new TextView(ctx);
            info.setTextSize(12);
            info.setTextColor(AppColors.text1());
            String showContent = t.content != null && t.content.length() > 40
                    ? t.content.substring(0, 40) + "..."
                    : t.content;
            info.setText(statusTag + "[" + typeLabel + "] " + timeStr + " -> " + targetDesc + "\n" + showContent);
            card.addView(info);

            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(d, 6), 0, 0);

            Button toggleBtn = smallBtn(ctx, d, t.enabled ? "暂停" : "启用",
                    t.enabled ? 0xFFE67E22 : 0xFF27AE60);
            toggleBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.enableTask(t.id, !t.enabled);
                refreshTaskList(ctx, d, taskList);
            });
            actions.addView(toggleBtn);

            View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(dp(d, 6), 0)); actions.addView(sp);

            Button sendNowBtn = smallBtn(ctx, d, "立即发送", 0xFF2980B9);
            sendNowBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.scheduleTask(t);
                Toast.makeText(ctx, "已加入发送队列", Toast.LENGTH_SHORT).show();
            });
            actions.addView(sendNowBtn);

            View sp2 = new View(ctx); sp2.setLayoutParams(new LinearLayout.LayoutParams(dp(d, 6), 0)); actions.addView(sp2);

            Button delBtn = smallBtn(ctx, d, "删除", 0xFFE74C3C);
            delBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.removeTask(t.id);
                refreshTaskList(ctx, d, taskList);
            });
            actions.addView(delBtn);

            card.addView(actions);
            taskList.addView(card);
        }

        if (tasks.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无定时任务");
            empty.setTextSize(12);
            empty.setTextColor(AppColors.text2());
            empty.setPadding(dp(d, 12), dp(d, 16), 0, dp(d, 16));
            taskList.addView(empty);
        }
    }

    private static void refreshDraftList(Context ctx, float d, LinearLayout draftList) {
        draftList.removeAllViews();
        List<ScheduleBroadcast.Task> drafts = ScheduleBroadcast.getDrafts();

        for (final ScheduleBroadcast.Task d2 : drafts) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(AppColors.card());
            row.setPadding(dp(d, 10), dp(d, 6), dp(d, 10), dp(d, 6));
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(d, 4));
            row.setLayoutParams(rowLp);

            String typeLabel;
            switch (d2.msgType) {
                case 1: typeLabel = "文本"; break;
                case 3: typeLabel = "图片"; break;
                case 34: typeLabel = "语音"; break;
                case 43: typeLabel = "视频"; break;
                case 47: typeLabel = "表情"; break;
                case 49: typeLabel = "AppMsg"; break;
                default: typeLabel = "类型" + d2.msgType;
            }

            TextView info = new TextView(ctx);
            info.setTextSize(12);
            info.setTextColor(AppColors.text1());
            String display = d2.content != null && d2.content.length() > 25
                    ? d2.content.substring(0, 25) + "..."
                    : d2.content;
            info.setText("[" + typeLabel + "] " + display);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(info);

            Button useBtn = smallBtn(ctx, d, "使用", 0xFF27AE60);
            useBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.addTask(d2);
                ScheduleBroadcast.removeDraft(d2.id);
                refreshDraftList(ctx, d, draftList);
            });
            row.addView(useBtn);

            View sp = new View(ctx); sp.setLayoutParams(new LinearLayout.LayoutParams(dp(d, 4), 0)); row.addView(sp);

            Button delBtn = smallBtn(ctx, d, "删除", 0xFFE74C3C);
            delBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.removeDraft(d2.id);
                refreshDraftList(ctx, d, draftList);
            });
            row.addView(delBtn);

            draftList.addView(row);
        }

        if (drafts.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无草稿");
            empty.setTextSize(12);
            empty.setTextColor(AppColors.text2());
            empty.setPadding(dp(d, 12), dp(d, 8), 0, dp(d, 8));
            draftList.addView(empty);
        }
    }

    private static TextView sectionLabel(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setPadding(0, dp(d, 16), 0, dp(d, 8));
        return tv;
    }

    private static Button smallBtn(Context ctx, float d, String text, int color) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(d, 10), dp(d, 4), dp(d, 10), dp(d, 4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(d, 4));
        bg.setColor(color);
        b.setBackground(bg);
        return b;
    }

    private static Button coloredBtn(Context ctx, float d, String text, int color) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(d, 16), dp(d, 8), dp(d, 16), dp(d, 8));
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(d, 6));
        bg.setColor(color);
        b.setBackground(bg);
        return b;
    }

    private static int dp(float d, int v) { return (int)(v * d + 0.5f); }
}
