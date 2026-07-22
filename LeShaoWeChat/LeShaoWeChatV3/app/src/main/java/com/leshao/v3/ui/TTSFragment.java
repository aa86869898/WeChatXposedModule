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

public class TTSFragment extends Fragment {

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

        root.addView(sectionLabel("语音播报设置"));

        // 总开关
        root.addView(switchRow("播报总开关", mCfg.masterSwitch, (v, on) -> {
            mCfg.masterSwitch = on; mCfg.save(mPrefs);
        }));

        // TTS 引擎选择
        root.addView(selectorRow("TTS 引擎", new String[]{"系统TTS", "配音引擎", "五音"}, mCfg.ttsEngine.equals("peiyin") ? 1 : mCfg.ttsEngine.equals("wusound") ? 2 : 0, idx -> {
            mCfg.ttsEngine = idx == 1 ? "peiyin" : idx == 2 ? "wusound" : "system";
            mCfg.save(mPrefs);
        }));

        // API Key
        root.addView(editRow("配音API Key", mCfg.peiyinApiKey, s -> { mCfg.peiyinApiKey = s; mCfg.save(mPrefs); }));

        // Voice ID
        root.addView(editRow("配音Voice ID", mCfg.peiyinVoiceId, s -> { mCfg.peiyinVoiceId = s; mCfg.save(mPrefs); }));

        // 播报间隔
        root.addView(editRow("播报间隔(ms)", String.valueOf(mCfg.announceIntervalMs), s -> {
            try { mCfg.announceIntervalMs = Integer.parseInt(s); mCfg.save(mPrefs); } catch (Throwable ignored) {}
        }));

        // 文本熔断
        root.addView(editRow("文本熔断字数", String.valueOf(mCfg.textCutoffLen), s -> {
            try { mCfg.textCutoffLen = Integer.parseInt(s); mCfg.save(mPrefs); } catch (Throwable ignored) {}
        }));

        // 播报类型
        root.addView(sectionLabel("播报消息类型"));
        String[] types = {"文字", "图片", "语音", "视频", "红包", "转账", "名片", "文件", "位置", "表情"};
        LinearLayout typeGrid = new LinearLayout(getContext());
        typeGrid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < types.length; i++) {
            final int bit = 1 << (i + 1);
            typeGrid.addView(checkRow(types[i], (mCfg.announceTypeMask & bit) != 0, (v, checked) -> {
                if (checked) mCfg.announceTypeMask |= bit;
                else mCfg.announceTypeMask &= ~bit;
                mCfg.save(mPrefs);
            }));
        }
        root.addView(typeGrid);

        // 免打扰
        root.addView(sectionLabel("免打扰设置"));
        root.addView(switchRow("开启免打扰", mCfg.quietEnabled, (v, on) -> {
            mCfg.quietEnabled = on; mCfg.save(mPrefs);
        }));
        root.addView(editRow("开始时间(HH:MM)", mCfg.quietStart, s -> {
            mCfg.quietStart = s; mCfg.save(mPrefs);
        }));
        root.addView(editRow("结束时间(HH:MM)", mCfg.quietEnd, s -> {
            mCfg.quietEnd = s; mCfg.save(mPrefs);
        }));

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(18);
        tv.setPadding(0, dp(16), 0, dp(8));
        tv.getPaint().setFakeBoldText(true);
        return tv;
    }

    private LinearLayout switchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(getContext()); sw.setChecked(checked); sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private LinearLayout editRow(String label, String value, EditCallback cb) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f));
        row.addView(tv);
        EditText et = new EditText(getContext()); et.setText(value); et.setTextSize(14);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { cb.onChange(s.toString()); }
        });
        row.addView(et);
        return row;
    }

    private LinearLayout checkRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(2), 0, dp(2));
        CheckBox cb = new CheckBox(getContext()); cb.setText(label); cb.setChecked(checked); cb.setOnCheckedChangeListener(listener);
        row.addView(cb);
        return row;
    }

    private LinearLayout selectorRow(String label, String[] options, int selected, SelectorCallback cb) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.3f));
        Spinner sp = new Spinner(getContext());
        sp.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, options));
        sp.setSelection(selected);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { cb.onSelect(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        row.addView(sp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));
        return row;
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density + 0.5f); }

    interface EditCallback { void onChange(String value); }
    interface SelectorCallback { void onSelect(int index); }
}
