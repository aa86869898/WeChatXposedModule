package com.leshao.v3.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
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
        root.setPadding((int)(8*d), (int)(16*d), (int)(8*d), (int)(16*d));

        root.addView(sectionLabel("语音播报设置", d));

        LinearLayout card1 = makeCard(d);
        card1.addView(switchRow(d, "播报总开关", "启用后将在锁屏/通知栏播报新消息内容", mCfg.masterSwitch, (v, on) -> {
            mCfg.masterSwitch = on; mCfg.save(mPrefs);
        }, null));
        card1.addView(candyDivider(getContext(), d));
        card1.addView(selectorRow("TTS 引擎", new String[]{"系统TTS", "配音引擎", "五音"}, mCfg.ttsEngine.equals("peiyin") ? 1 : mCfg.ttsEngine.equals("wusound") ? 2 : 0, d, idx -> {
            mCfg.ttsEngine = idx == 1 ? "peiyin" : idx == 2 ? "wusound" : "system";
            mCfg.save(mPrefs);
        }));
        root.addView(card1);

        root.addView(candyDivider(getContext(), d));
        root.addView(sectionLabel("配音配置", d));
        LinearLayout card2 = makeCard(d);
        card2.addView(editRow(d, "配音API Key", mCfg.peiyinApiKey, s -> { mCfg.peiyinApiKey = s; mCfg.save(mPrefs); }));
        card2.addView(candyDivider(getContext(), d));
        card2.addView(editRow(d, "配音Voice ID", mCfg.peiyinVoiceId, s -> { mCfg.peiyinVoiceId = s; mCfg.save(mPrefs); }));
        root.addView(card2);

        root.addView(candyDivider(getContext(), d));
        root.addView(sectionLabel("播报参数", d));
        LinearLayout card3 = makeCard(d);
        card3.addView(editRow(d, "播报间隔(ms)", String.valueOf(mCfg.announceIntervalMs), s -> {
            try { mCfg.announceIntervalMs = Integer.parseInt(s); mCfg.save(mPrefs); } catch (Throwable ignored) {}
        }));
        card3.addView(candyDivider(getContext(), d));
        card3.addView(editRow(d, "文本熔断字数", String.valueOf(mCfg.textCutoffLen), s -> {
            try { mCfg.textCutoffLen = Integer.parseInt(s); mCfg.save(mPrefs); } catch (Throwable ignored) {}
        }));
        root.addView(card3);

        root.addView(candyDivider(getContext(), d));
        root.addView(sectionLabel("播报消息类型", d));
        LinearLayout card4 = makeCard(d);
        String[] types = {"文字", "图片", "语音", "视频", "红包", "转账", "名片", "文件", "位置", "表情"};
        for (int i = 0; i < types.length; i++) {
            if (i > 0) card4.addView(candyDivider(getContext(), d));
            final int bit = 1 << (i + 1);
            final String typeName = types[i];
            card4.addView(subSwitchRow(d, typeName, (mCfg.announceTypeMask & bit) != 0, (v, checked) -> {
                if (checked) mCfg.announceTypeMask |= bit;
                else mCfg.announceTypeMask &= ~bit;
                mCfg.save(mPrefs);
            }));
        }
        root.addView(card4);

        root.addView(candyDivider(getContext(), d));
        root.addView(sectionLabel("特殊消息播报", d));
        LinearLayout card5 = makeCard(d);
        card5.addView(subSwitchRow(d, "被@时播报", mCfg.announceAt, (v, checked) -> {
            mCfg.announceAt = checked; mCfg.save(mPrefs);
        }));
        card5.addView(candyDivider(getContext(), d));
        card5.addView(subSwitchRow(d, "小程序消息播报", mCfg.announceMiniProgram, (v, checked) -> {
            mCfg.announceMiniProgram = checked; mCfg.save(mPrefs);
        }));
        card5.addView(candyDivider(getContext(), d));
        card5.addView(subSwitchRow(d, "视频号消息播报", mCfg.announceVideoChannel, (v, checked) -> {
            mCfg.announceVideoChannel = checked; mCfg.save(mPrefs);
        }));
        card5.addView(candyDivider(getContext(), d));
        card5.addView(subSwitchRow(d, "聊天记录播报", mCfg.announceChatHistory, (v, checked) -> {
            mCfg.announceChatHistory = checked; mCfg.save(mPrefs);
        }));
        root.addView(card5);

        root.addView(candyDivider(getContext(), d));
        root.addView(sectionLabel("免打扰设置", d));
        LinearLayout card6 = makeCard(d);
        card6.addView(switchRow(d, "开启免打扰", "启用后在指定时间段内暂停语音播报", mCfg.quietEnabled, (v, on) -> {
            mCfg.quietEnabled = on; mCfg.save(mPrefs);
        }, null));
        card6.addView(candyDivider(getContext(), d));
        card6.addView(editRow(d, "开始时间(HH:MM)", mCfg.quietStart, s -> {
            mCfg.quietStart = s; mCfg.save(mPrefs);
        }));
        card6.addView(candyDivider(getContext(), d));
        card6.addView(editRow(d, "结束时间(HH:MM)", mCfg.quietEnd, s -> {
            mCfg.quietEnd = s; mCfg.save(mPrefs);
        }));
        root.addView(card6);

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    // === 组件工厂 (统一 ChatEnhanceFragment 风格) ===

    private LinearLayout makeCard(float d) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(AppColors.bg());
        card.setPadding((int)(2*d), (int)(2*d), (int)(2*d), (int)(2*d));
        return card;
    }

    private TextView sectionLabel(String text, float d) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8*d));
        return tv;
    }

    private LinearLayout switchRow(float d, String title, String desc, boolean checked,
                                    CompoundButton.OnCheckedChangeListener l, View.OnClickListener config) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
        row.setBackgroundColor(AppColors.card());

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView tv = new TextView(getContext());
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(getContext());
            dv.setText(desc);
            dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3*d), 0, 0);
            textCol.addView(dv);
        }

        row.addView(textCol);

        if (config != null) {
            TextView btn = new TextView(getContext());
            btn.setText("[设置]");
            btn.setTextSize(12);
            btn.setTextColor(AppColors.accent());
            btn.setPadding((int)(6*d), 0, (int)(6*d), 0);
            btn.setPaintFlags(btn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            btn.setOnClickListener(config);
            row.addView(btn);
        }

        Switch sw = CandyUi.newSwitch(getContext());
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
        row.setBackgroundColor(AppColors.card());

        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tv);

        Switch sw = CandyUi.newSwitch(getContext());
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
        row.setBackgroundColor(AppColors.card());

        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        row.addView(tv);

        EditText et = new EditText(getContext());
        et.setText(value);
        et.setTextSize(14);
        et.setTextColor(AppColors.text1());
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(AppColors.inputBg());
        etBg.setCornerRadius((int)(6 * d));
        etBg.setStroke((int)(1.5f * d), AppColors.candyPink());
        et.setBackground(etBg);
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
        row.setBackgroundColor(AppColors.card());

        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
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

    private static View candyDivider(Context ctx, float d) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.candyPink(), AppColors.candyYellow(), AppColors.accent(), AppColors.candyPink()});
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(1.5f * d));
        lp.setMargins((int)(12 * d), (int)(6 * d), (int)(12 * d), (int)(6 * d));
        v.setLayoutParams(lp);
        v.setBackground(gd);
        return v;
    }

    interface EditCallback { void onChange(String value); }
    interface SelectorCallback { void onSelect(int index); }
}
