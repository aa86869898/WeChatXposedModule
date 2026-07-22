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

public class AIFragment extends Fragment {

    private ModuleConfig mCfg;
    private SharedPreferences mPrefs;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mPrefs = ContextManager.getPrefs();
        mCfg = ModuleConfig.load(mPrefs);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(sLabel("AI 助手"));

        // DeepSeek
        root.addView(switchRow("DeepSeek 对话", mCfg.deepseekEnabled, (v, on) -> {
            mCfg.deepseekEnabled = on; mCfg.save(mPrefs);
        }));
        root.addView(editRow("DeepSeek API Key", mCfg.deepseekApiKey, s -> {
            mCfg.deepseekApiKey = s; mCfg.save(mPrefs);
        }));

        // 火山方舟
        root.addView(switchRow("AI 图片生成", mCfg.imageGenEnabled, (v, on) -> {
            mCfg.imageGenEnabled = on; mCfg.save(mPrefs);
        }));
        root.addView(editRow("火山方舟 API Key", mCfg.arkApiKey, s -> {
            mCfg.arkApiKey = s; mCfg.save(mPrefs);
        }));

        root.addView(sLabel("使用说明"));
        root.addView(infoRow("群聊中 @机器人 提问", "自动调用 DeepSeek 回复"));
        root.addView(infoRow("私聊发送 AI+内容", "自动调用 AI 回复"));
        root.addView(infoRow("未配置 API Key", "功能不可用，需自行申请"));

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private TextView sLabel(String t) {
        TextView tv = new TextView(getContext()); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(16), 0, dp(8)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private LinearLayout switchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(getContext()); sw.setChecked(checked); sw.setOnCheckedChangeListener(l);
        row.addView(sw); return row;
    }

    private LinearLayout editRow(String label, String value, EditCallback cb) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        row.addView(tv);
        EditText et = new EditText(getContext()); et.setText(value); et.setTextSize(14);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { cb.onChange(s.toString()); }
        });
        row.addView(et); return row;
    }

    private LinearLayout infoRow(String cmd, String desc) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(4), 0, dp(4));
        TextView t1 = new TextView(getContext()); t1.setText(cmd); t1.setTextSize(13);
        t1.getPaint().setFakeBoldText(true); row.addView(t1);
        TextView t2 = new TextView(getContext()); t2.setText(" - " + desc); t2.setTextSize(13);
        t2.setTextColor(0xFF666666); row.addView(t2); return row;
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density + 0.5f); }
    interface EditCallback { void onChange(String value); }
}
