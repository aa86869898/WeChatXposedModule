package com.leshao.v3.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.GroupFeatures;
import com.leshao.v3.model.ModuleConfig;
import java.util.ArrayList;
import java.util.List;

public class GroupGuardFragment extends Fragment {

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
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        root.addView(sLabel("群管理"));

        // 入群欢迎
        root.addView(switchRow("入群欢迎", mCfg.welcomeEnabled, (v, on) -> {
            mCfg.welcomeEnabled = on; mCfg.save(mPrefs);
        }));
        root.addView(editRow("欢迎语", mCfg.welcomeMsg, s -> { mCfg.welcomeMsg = s; mCfg.save(mPrefs); }));

        // 自动踢人
        root.addView(switchRow("自动踢人", mCfg.autoKickEnabled, (v, on) -> {
            mCfg.autoKickEnabled = on; mCfg.save(mPrefs);
        }));
        root.addView(editRow("违规阈值", String.valueOf(mCfg.kickThreshold), s -> {
            try { mCfg.kickThreshold = Integer.parseInt(s); mCfg.save(mPrefs); } catch (Throwable ignored) {}
        }));

        root.addView(candyDivider(getContext(), d));

        // 广告关键词
        root.addView(sLabel("广告关键词"));
        LinearLayout kwLayout = new LinearLayout(getContext());
        kwLayout.setOrientation(LinearLayout.VERTICAL);
        List<String> adKwList = new ArrayList<>(mCfg.adKeywords);
        refreshKeywordList(kwLayout, adKwList);
        root.addView(kwLayout);

        // 添加关键词
        LinearLayout addRow = new LinearLayout(getContext());
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText input = new EditText(getContext()); input.setHint("输入关键词");
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(AppColors.inputBg());
        inputBg.setCornerRadius((int)(6 * d));
        inputBg.setStroke((int)(1.5f * d), AppColors.candyPink());
        input.setBackground(inputBg);
        input.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addRow.addView(input);
        Button addBtn = new Button(getContext()); addBtn.setText("添加");
        addBtn.setPaintFlags(addBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        addBtn.setOnClickListener(v -> {
            String kw = input.getText().toString().trim();
            if (!kw.isEmpty()) {
                mCfg.adKeywords.add(kw);
                mCfg.save(mPrefs);
                refreshKeywordList(kwLayout, new ArrayList<>(mCfg.adKeywords));
                input.setText("");
            }
        });
        addRow.addView(addBtn);
        root.addView(addRow);

        root.addView(candyDivider(getContext(), d));

        // 群管增强
        root.addView(sLabel("群管增强"));
        root.addView(switchRow("群功能增强 (踢人/禁言/群发/防炸群)", mCfg.groupFeaturesEnabled, (v, on) -> {
            mCfg.groupFeaturesEnabled = on; mCfg.save(mPrefs); GroupFeatures.setEnabled(on);
        }));

        root.addView(candyDivider(getContext(), d));

        // 黑名单
        root.addView(sLabel("黑名单 wxid (每行一个)"));
        StringBuilder blText = new StringBuilder();
        for (String w : mCfg.blacklistWxids) { blText.append(w).append("\n"); }
        EditText blEdit = new EditText(getContext()); blEdit.setText(blText.toString());
        GradientDrawable blBg = new GradientDrawable();
        blBg.setColor(AppColors.inputBg());
        blBg.setCornerRadius((int)(6 * d));
        blBg.setStroke((int)(1.5f * d), AppColors.candyPink());
        blEdit.setBackground(blBg);
        blEdit.setMinLines(3);
        blEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                mCfg.blacklistWxids.clear();
                for (String line : s.toString().split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) mCfg.blacklistWxids.add(t);
                }
                mCfg.save(mPrefs);
            }
        });
        root.addView(blEdit);

        ScrollView sv = new ScrollView(getContext());
        sv.addView(root);
        return sv;
    }

    private void refreshKeywordList(LinearLayout container, List<String> keywords) {
        container.removeAllViews();
        for (String kw : keywords) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(getContext()); tv.setText(kw); tv.setTextSize(14);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(tv);
            Button del = new Button(getContext()); del.setText("删除");
            del.setPaintFlags(del.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            del.setOnClickListener(v -> {
                mCfg.adKeywords.remove(kw);
                mCfg.save(mPrefs);
                refreshKeywordList(container, new ArrayList<>(mCfg.adKeywords));
            });
            row.addView(del);
            container.addView(row);
        }
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
        Switch sw = CandyUi.newSwitch(getContext()); sw.setChecked(checked); sw.setOnCheckedChangeListener(l);
        row.addView(sw); return row;
    }

    private LinearLayout editRow(String label, String value, EditCallback cb) {
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(getContext()); tv.setText(label); tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        row.addView(tv);
        EditText et = new EditText(getContext()); et.setText(value); et.setTextSize(14);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(AppColors.inputBg());
        etBg.setCornerRadius(dp(6));
        etBg.setStroke((int)(1.5f * dp(1)), AppColors.candyPink());
        et.setBackground(etBg);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { cb.onChange(s.toString()); }
        });
        row.addView(et); return row;
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

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density + 0.5f); }
    interface EditCallback { void onChange(String value); }
}
