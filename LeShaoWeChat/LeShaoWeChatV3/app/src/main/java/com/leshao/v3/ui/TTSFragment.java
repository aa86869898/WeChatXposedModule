package com.leshao.v3.ui;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
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

        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(16*d), (int)(16*d), (int)(16*d), (int)(16*d));

        root.addView(sectionLabel("语音播报设置", d));

        LinearLayout card1 = makeCard(d);
        card1.addView(switchRow(d, "播报总开关", "启用后将在锁屏/通知栏播报新消息内容", mCfg.masterSwitch, (v, on) -> {
            mCfg.masterSwitch = on; mCfg.save(mPrefs);
        }, null));
        card1.addView(itemDivider(d));
        card1.addView(selectorRow("TTS 引擎", new String[]{"系统TTS", "配音引擎", "五音"}, mCfg.ttsEngine.equals("peiyin") ? 1 : mCfg.ttsEngine.equals("wusound") ? 2 : 0, d, idx -> {
            mCfg.ttsEngine = idx == 1 ? "peiyin" : idx == 2 ? "wusound" : "system";
            mCfg.save(mPrefs);
        }));
        root.addView(card1);

        root.addView(spacerV(d, 12));
        root.addView(sectionLabel("配音配置", d));
        LinearLayout card2 = makeCard(d);
        card2.addView(editRow(d, "配音API Key", mCfg.peiyinApiKey, s -> { mCfg.peiyinApiKey = s; mCfg.save(mPrefs); }));
        card2.addView(itemDivider(d));
        card2.addView(editRow(d, "配音Voice ID", mCfg.peiyinVoiceId, s -> { mCfg.peiyinVoiceId = s; mCfg.save(mPrefs); }));
        root.addView(card2);

        root.addView(spacerV(d, 12));
        root.addView(sectionLabel("播报参数", d));
        LinearLayout card3 = makeCard(d);
        card3.addView(editRow(d, "播报间隔(ms)", String.valueOf(mCfg.announceIntervalMs), s -> {
            try { mCfg.announceIntervalMs = Integer.parseInt(s); mCfg.save(mPrefs); } catch (Throwable ignored) {}
        }));
        card3.addView(itemDivider(d));
        card3.addView(editRow(d, "文本熔断字数", String.valueOf(mCfg.textCutoffLen), s -> {
            try { mCfg.textCutoffLen = Integer.parseInt(s); mCfg.save(mPrefs); } catch (Throwable ignored) {}
        }));
        root.addView(card3);

        root.addView(spacerV(d, 12));
        root.addView(sectionLabel("播报消息类型", d));
        LinearLayout card4 = makeCard(d);
        String[] types = {"文字", "图片", "语音", "视频", "红包", "转账", "名片", "文件", "位置", "表情"};
        for (int i = 0; i < types.length; i++) {
            if (i > 0) card4.addView(itemDivider(d));
            final int bit = 1 << (i + 1);
            final String typeName = types[i];
            card4.addView(subSwitchRow(d, typeName, (mCfg.announceTypeMask & bit) != 0, (v, checked) -> {
                if (checked) mCfg.announceTypeMask |= bit;
                else mCfg.announceTypeMask &= ~bit;
                mCfg.save(mPrefs);
            }));
        }
        root.addView(card4);

        root.addView(spacerV(d, 12));
        root.addView(sectionLabel("免打扰设置", d));
        LinearLayout card5 = makeCard(d);
        card5.addView(switchRow(d, "开启免打扰", "启用后在指定时间段内暂停语音播报", mCfg.quietEnabled, (v, on) -> {
            mCfg.quietEnabled = on; mCfg.save(mPrefs);
        }, null));
        card5.addView(itemDivider(d));
        card5.addView(editRow(d, "开始时间(HH:MM)", mCfg.quietStart, s -> {
            mCfg.quietStart = s; mCfg.save(mPrefs);
        }));
        card5.addView(itemDivider(d));
        card5.addView(editRow(d, "结束时间(HH:MM)", mCfg.quietEnd, s -> {
            mCfg.quietEnd = s; mCfg.save(mPrefs);
        }));
        root.addView(card5);

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    // === 组件工厂 (统一 ChatEnhanceFragment 风格) ===

    private LinearLayout makeCard(float d) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFFF5F5F5);
        card.setPadding((int)(2*d), (int)(2*d), (int)(2*d), (int)(2*d));
        return card;
    }

    private TextView sectionLabel(String text, float d) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF999999);
        tv.setPadding(0, 0, 0, (int)(8*d));
        return tv;
    }

    private LinearLayout switchRow(float d, String title, String desc, boolean checked,
                                    CompoundButton.OnCheckedChangeListener l, View.OnClickListener config) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
        row.setBackgroundColor(0xFFFFFFFF);

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tv = new TextView(getContext());
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(0xFF1A1A1A);
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(getContext());
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(0xFF999999);
            dv.setPadding(0, (int)(3*d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        if (config != null) {
            TextView btn = new TextView(getContext());
            btn.setText("[设置]");
            btn.setTextSize(12);
            btn.setTextColor(0xFF4A90D9);
            btn.setPadding((int)(6*d), 0, (int)(6*d), 0);
            btn.setOnClickListener(config);
            row.addView(btn);
        }

        Switch sw = new Switch(getContext());
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    private LinearLayout subSwitchRow(float d, String label, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
        row.setBackgroundColor(0xFFFFFFFF);

        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(0xFF333333);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tv);

        Switch sw = new Switch(getContext());
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    private LinearLayout editRow(float d, String label, String value, EditCallback cb) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
        row.setBackgroundColor(0xFFFFFFFF);

        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(0xFF333333);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        row.addView(tv);

        EditText et = new EditText(getContext());
        et.setText(value);
        et.setTextSize(14);
        et.setTextColor(0xFF1A1A1A);
        et.setBackgroundColor(0xFFF5F5F5);
        et.setPadding((int)(10*d), (int)(8*d), (int)(10*d), (int)(8*d));
        et.setSingleLine(true);
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { cb.onChange(s.toString()); }
        });
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        row.addView(et);

        return row;
    }

    private LinearLayout selectorRow(String label, String[] options, int selected, float d, SelectorCallback cb) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
        row.setBackgroundColor(0xFFFFFFFF);

        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(0xFF1A1A1A);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f));
        row.addView(tv);

        Spinner sp = new Spinner(getContext());
        sp.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, options));
        sp.setSelection(selected);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { cb.onSelect(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));
        row.addView(sp);
        return row;
    }

    private View itemDivider(float d) {
        View v = new View(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int)(1*d));
        lp.setMargins((int)(14*d), 0, (int)(14*d), 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(AppColors.divider());
        return v;
    }

    private View spacerV(float d, int dpVal) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }

    interface EditCallback { void onChange(String value); }
    interface SelectorCallback { void onSelect(int index); }
}
