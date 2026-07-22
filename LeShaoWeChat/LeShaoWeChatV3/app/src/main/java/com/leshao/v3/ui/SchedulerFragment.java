package com.leshao.v3.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.model.ScheduledTask;
import java.util.ArrayList;
import java.util.List;

public class SchedulerFragment extends Fragment {

    private ModuleConfig mCfg;
    private SharedPreferences mPrefs;
    private LinearLayout mTaskList;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mPrefs = ContextManager.getPrefs();
        mCfg = ModuleConfig.load(mPrefs);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(sLabel("定时任务"));

        mTaskList = new LinearLayout(getContext());
        mTaskList.setOrientation(LinearLayout.VERTICAL);
        root.addView(mTaskList);

        // 添加任务表单
        root.addView(sLabel("新建任务"));
        EditText wxidEdit = new EditText(getContext()); wxidEdit.setHint("目标 wxid");
        EditText contentEdit = new EditText(getContext()); contentEdit.setHint("发送内容");
        EditText hourEdit = new EditText(getContext()); hourEdit.setHint("小时(0-23)");
        EditText minEdit = new EditText(getContext()); minEdit.setHint("分钟(0-59)");
        root.addView(wxidEdit); root.addView(contentEdit); root.addView(hourEdit); root.addView(minEdit);

        Button addBtn = new Button(getContext()); addBtn.setText("添加任务");
        addBtn.setOnClickListener(v -> {
            try {
                String wxid = wxidEdit.getText().toString().trim();
                String content = contentEdit.getText().toString().trim();
                int h = Integer.parseInt(hourEdit.getText().toString().trim());
                int m = Integer.parseInt(minEdit.getText().toString().trim());
                if (!wxid.isEmpty() && !content.isEmpty()) {
                    ScheduledTask t = new ScheduledTask(null, wxid, content, h, m, 127, true);
                    mCfg.scheduledTasks.add(t);
                    mCfg.save(mPrefs);
                    refreshTaskList();
                    wxidEdit.setText(""); contentEdit.setText(""); hourEdit.setText(""); minEdit.setText("");
                }
            } catch (Throwable ignored) {}
        });
        root.addView(addBtn);

        refreshTaskList();

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private void refreshTaskList() {
        mTaskList.removeAllViews();
        for (int i = 0; i < mCfg.scheduledTasks.size(); i++) {
            final int idx = i;
            ScheduledTask t = mCfg.scheduledTasks.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView info = new TextView(getContext());
            info.setText(String.format("%02d:%02d -> %s: %s", t.hour, t.minute, t.targetWxid, t.content));
            info.setTextSize(12);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(info);

            Button del = new Button(getContext()); del.setText("删除");
            del.setOnClickListener(v2 -> {
                mCfg.scheduledTasks.remove(idx);
                mCfg.save(mPrefs);
                refreshTaskList();
            });
            row.addView(del);

            mTaskList.addView(row);
        }
    }

    private TextView sLabel(String t) {
        TextView tv = new TextView(getContext()); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(16), 0, dp(8)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density + 0.5f); }
}
