package com.leshao.v3.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.leshao.v3.hook.ScheduleBroadcast;
import java.util.ArrayList;
import java.util.List;

public class SchedulerFragment extends Fragment {

    private LinearLayout mTaskList;
    private LinearLayout mDraftList;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        // 状态栏
        LinearLayout statusRow = new LinearLayout(getContext());
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setPadding(0, 0, 0, dp(12));
        TextView status = new TextView(getContext());
        status.setTextSize(13);
        status.setText("任务: " + ScheduleBroadcast.getTaskCount() + " | 草稿: " + ScheduleBroadcast.getDraftCount()
                + " | 群数: " + ScheduleBroadcast.getGroupCount()
                + " | 今日: " + ScheduleBroadcast.getDailyCount());
        statusRow.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button stopBtn = new Button(getContext()); stopBtn.setText("紧急停止");
        stopBtn.setOnClickListener(v -> ScheduleBroadcast.emergencyStop());
        statusRow.addView(stopBtn);
        Button refreshBtn = new Button(getContext()); refreshBtn.setText("刷新");
        refreshBtn.setOnClickListener(v -> refreshAll());
        statusRow.addView(refreshBtn);
        root.addView(statusRow);

        // 新建任务
        root.addView(sLabel("新建定时任务"));
        LinearLayout form = new LinearLayout(getContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(0, dp(8), 0, dp(8));

        LinearLayout row1 = new LinearLayout(getContext());
        Spinner typeSpinner = new Spinner(getContext());
        typeSpinner.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item,
                new String[]{"文本消息", "图片消息", "语音消息", "视频消息", "表情消息", "链接/AppMsg"}));
        row1.addView(typeSpinner);
        form.addView(row1);

        EditText contentEdit = new EditText(getContext()); contentEdit.setHint("发送内容"); contentEdit.setMinLines(2);
        form.addView(contentEdit);

        LinearLayout row2 = new LinearLayout(getContext());
        EditText hourEdit = new EditText(getContext()); hourEdit.setHint("时(0-23)");
        hourEdit.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.3f));
        row2.addView(hourEdit);
        EditText minEdit = new EditText(getContext()); minEdit.setHint("分(0-59)");
        minEdit.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.3f));
        row2.addView(minEdit);
        EditText dateEdit = new EditText(getContext()); dateEdit.setHint("日期(MM-dd)");
        dateEdit.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f));
        row2.addView(dateEdit);
        form.addView(row2);

        EditText wxidEdit = new EditText(getContext()); wxidEdit.setHint("目标群wxid(逗号分隔，留空=全部群)");
        form.addView(wxidEdit);

        LinearLayout row3 = new LinearLayout(getContext());
        CheckBox sendAllCb = new CheckBox(getContext()); sendAllCb.setText("发送全部群");
        CheckBox randCb = new CheckBox(getContext()); randCb.setText("随机顺序");
        row3.addView(sendAllCb); row3.addView(randCb);
        form.addView(row3);

        root.addView(form);

        LinearLayout btnRow = new LinearLayout(getContext());
        Button addBtn = new Button(getContext()); addBtn.setText("添加任务");
        addBtn.setOnClickListener(v -> {
            try {
                int[] types = {1, 3, 34, 43, 47, 49};
                int msgType = types[typeSpinner.getSelectedItemPosition()];

                ScheduleBroadcast.Task t = new ScheduleBroadcast.Task();
                t.msgType = msgType;
                t.content = contentEdit.getText().toString().trim();
                t.sendAllGroups = sendAllCb.isChecked();
                t.randomOrder = randCb.isChecked();

                String hourStr = hourEdit.getText().toString().trim();
                String minStr = minEdit.getText().toString().trim();
                int hour = hourStr.isEmpty() ? 0 : Integer.parseInt(hourStr);
                int min = minStr.isEmpty() ? 0 : Integer.parseInt(minStr);

                java.util.Calendar cal = java.util.Calendar.getInstance();
                String dateStr = dateEdit.getText().toString().trim();
                if (!dateStr.isEmpty()) {
                    String[] parts = dateStr.split("-");
                    cal.set(java.util.Calendar.MONTH, Integer.parseInt(parts[0]) - 1);
                    cal.set(java.util.Calendar.DAY_OF_MONTH, Integer.parseInt(parts[1]));
                }
                cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
                cal.set(java.util.Calendar.MINUTE, min);
                cal.set(java.util.Calendar.SECOND, 0);
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
                    refreshAll();
                    Toast.makeText(getContext(), "任务已添加", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "请指定目标群或勾选全部群", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ex) {
                Toast.makeText(getContext(), "输入格式错误", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(addBtn);

        Button draftBtn = new Button(getContext()); draftBtn.setText("保存草稿");
        draftBtn.setOnClickListener(v -> {
            try {
                int[] types = {1, 3, 34, 43, 47, 49};
                ScheduleBroadcast.Task t = new ScheduleBroadcast.Task();
                t.msgType = types[typeSpinner.getSelectedItemPosition()];
                t.content = contentEdit.getText().toString().trim();
                t.sendAllGroups = sendAllCb.isChecked();
                t.randomOrder = randCb.isChecked();
                String wxids = wxidEdit.getText().toString().trim();
                if (!wxids.isEmpty()) for (String w : wxids.split(",")) { String trim = w.trim(); if (!trim.isEmpty()) t.targetGroups.add(trim); }
                ScheduleBroadcast.saveDraft(t);
                contentEdit.setText(""); wxidEdit.setText("");
                refreshAll();
                Toast.makeText(getContext(), "草稿已保存", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
        btnRow.addView(draftBtn);
        root.addView(btnRow);

        // 任务列表
        root.addView(sLabel("定时任务列表"));
        mTaskList = new LinearLayout(getContext());
        mTaskList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mTaskList);

        // 草稿列表
        root.addView(sLabel("草稿箱"));
        mDraftList = new LinearLayout(getContext());
        mDraftList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mDraftList);

        root.addView(sLabel("模板库"));
        LinearLayout tplList = new LinearLayout(getContext());
        tplList.setOrientation(LinearLayout.VERTICAL);
        for (java.util.Map.Entry<String, String> e : ScheduleBroadcast.TEMPLATE_LIBRARY.entrySet()) {
            LinearLayout tplRow = new LinearLayout(getContext());
            tplRow.setOrientation(LinearLayout.HORIZONTAL);
            tplRow.setPadding(0, dp(4), 0, dp(4));
            TextView tplName = new TextView(getContext()); tplName.setText("[" + e.getKey() + "]"); tplName.setTextSize(13);
            tplName.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.3f));
            tplRow.addView(tplName);
            TextView tplCont = new TextView(getContext()); tplCont.setText(e.getValue()); tplCont.setTextSize(12);
            tplCont.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));
            tplRow.addView(tplCont);
            tplList.addView(tplRow);
        }
        root.addView(tplList);

        root.addView(sLabel("场景模板"));
        LinearLayout sceneList = new LinearLayout(getContext());
        sceneList.setOrientation(LinearLayout.VERTICAL);
        for (ScheduleBroadcast.SceneTemplate st : ScheduleBroadcast.SCENE_TEMPLATES) {
            TextView s = new TextView(getContext());
            s.setTextSize(12);
            s.setText(st.name + " @" + (st.hour == -1 ? "每小时" : st.hour + ":00") + " " + st.content);
            s.setPadding(0, dp(2), 0, dp(2));
            sceneList.addView(s);
        }
        root.addView(sceneList);

        refreshAll();

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private void refreshAll() {
        refreshTaskList();
        refreshDraftList();
    }

    private void refreshTaskList() {
        mTaskList.removeAllViews();
        List<ScheduleBroadcast.Task> tasks = ScheduleBroadcast.getAllTasks();
        for (int i = 0; i < tasks.size(); i++) {
            final int idx = i;
            final ScheduleBroadcast.Task t = tasks.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            row.setBackgroundColor(0x0A000000);

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
            String targetDesc = t.sendAllGroups ? "全部群" : (t.targetGroups.size() + "个群");
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            String timeStr = t.triggerTime > 0 ? sdf.format(new java.util.Date(t.triggerTime)) : "即时";
            String status = t.enabled ? "启用" : "暂停";

            TextView info = new TextView(getContext());
            info.setTextSize(12);
            info.setText("[" + status + "][" + typeLabel + "] " + timeStr + " -> " + targetDesc
                    + "\n" + (t.content != null && t.content.length() > 30 ? t.content.substring(0, 30) + "..." : t.content));
            row.addView(info);

            LinearLayout actions = new LinearLayout(getContext());
            actions.setOrientation(LinearLayout.HORIZONTAL);

            Button toggleBtn = new Button(getContext());
            toggleBtn.setText(t.enabled ? "暂停" : "启用");
            toggleBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.enableTask(t.id, !t.enabled);
                refreshTaskList();
            });
            actions.addView(toggleBtn);

            Button sendNowBtn = new Button(getContext()); sendNowBtn.setText("发送");
            sendNowBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.scheduleTask(t);
                Toast.makeText(getContext(), "已加入发送队列", Toast.LENGTH_SHORT).show();
            });
            actions.addView(sendNowBtn);

            Button delBtn = new Button(getContext()); delBtn.setText("删除");
            delBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.removeTask(t.id);
                refreshAll();
            });
            actions.addView(delBtn);

            row.addView(actions);
            mTaskList.addView(row);
        }
        if (tasks.isEmpty()) {
            TextView empty = new TextView(getContext()); empty.setText("暂无任务"); empty.setTextSize(13); empty.setPadding(dp(4), dp(8), 0, 0);
            mTaskList.addView(empty);
        }
    }

    private void refreshDraftList() {
        mDraftList.removeAllViews();
        List<ScheduleBroadcast.Task> drafts = ScheduleBroadcast.getDrafts();
        for (int i = 0; i < drafts.size(); i++) {
            final int idx = i;
            final ScheduleBroadcast.Task d = drafts.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));

            String typeLabel;
            switch (d.msgType) {
                case 1: typeLabel = "文本"; break;
                case 3: typeLabel = "图片"; break;
                case 34: typeLabel = "语音"; break;
                case 43: typeLabel = "视频"; break;
                case 47: typeLabel = "表情"; break;
                case 49: typeLabel = "AppMsg"; break;
                default: typeLabel = "类型" + d.msgType;
            }

            TextView info = new TextView(getContext());
            info.setTextSize(12);
            info.setText("[" + typeLabel + "] " + (d.content != null && d.content.length() > 20 ? d.content.substring(0, 20) + "..." : d.content));
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(info);

            Button useBtn = new Button(getContext()); useBtn.setText("使用");
            useBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.addTask(d);
                ScheduleBroadcast.removeDraft(d.id);
                refreshAll();
            });
            row.addView(useBtn);

            Button delBtn = new Button(getContext()); delBtn.setText("删除");
            delBtn.setOnClickListener(v2 -> {
                ScheduleBroadcast.removeDraft(d.id);
                refreshAll();
            });
            row.addView(delBtn);

            mDraftList.addView(row);
        }
        if (drafts.isEmpty()) {
            TextView empty = new TextView(getContext()); empty.setText("暂无草稿"); empty.setTextSize(13); empty.setPadding(dp(4), dp(8), 0, 0);
            mDraftList.addView(empty);
        }
    }

    private TextView sLabel(String t) {
        TextView tv = new TextView(getContext()); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(16), 0, dp(8)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density + 0.5f); }
}
