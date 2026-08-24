package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.GroupFeatures;
import com.leshao.v3.model.ModuleConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class GroupGuardPageView {

    public static View create(Context ctx, Activity parentAct) {
        final SharedPreferences prefs = ContextManager.getPrefs();
        final ModuleConfig cfg = ModuleConfig.load(prefs);
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(d, 16), dp(d, 16), dp(d, 16), dp(d, 16));

        root.addView(sLabel(ctx, d, "群管理"));

        // 入群欢迎
        root.addView(switchRow(ctx, d, "入群欢迎", cfg.welcomeEnabled, (v, on) -> {
            cfg.welcomeEnabled = on; cfg.save(prefs);
        }));
        root.addView(editRow(ctx, d, "欢迎语", cfg.welcomeMsg, s -> { cfg.welcomeMsg = s; cfg.save(prefs); }));
        LinearLayout typeRow = new LinearLayout(ctx);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeRow.setPadding(0, dp(d, 4), 0, dp(d, 4));
        TextView typeLbl = new TextView(ctx); typeLbl.setText("欢迎语类型"); typeLbl.setTextSize(14);
        typeLbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        typeRow.addView(typeLbl);
        Spinner typeSp = new Spinner(ctx);
        typeSp.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"文本消息", "图片消息 (待签名)"}));
        typeSp.setSelection(cfg.welcomeType == 1 ? 1 : 0);
        typeSp.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        typeSp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                cfg.welcomeType = pos; cfg.save(prefs);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        typeRow.addView(typeSp);
        root.addView(typeRow);

        root.addView(candyDivider(ctx, d));

        // 自动踢人
        root.addView(sLabel(ctx, d, "自动踢人"));
        root.addView(switchRow(ctx, d, "自动踢人", cfg.autoKickEnabled, (v, on) -> {
            cfg.autoKickEnabled = on; cfg.save(prefs);
        }));
        root.addView(editRow(ctx, d, "违规阈值", String.valueOf(cfg.kickThreshold), s -> {
            try { cfg.kickThreshold = Integer.parseInt(s); cfg.save(prefs); } catch (Throwable ignored) {}
        }));

        // 广告关键词
        root.addView(sLabel(ctx, d, "广告关键词 (命中计入违规)"));
        LinearLayout kwLayout = new LinearLayout(ctx);
        kwLayout.setOrientation(LinearLayout.VERTICAL);
        refreshKeywordList(ctx, kwLayout, cfg.adKeywords, cfg, prefs);
        root.addView(kwLayout);
        root.addView(addKeywordRow(ctx, d, kwLayout, cfg.adKeywords, cfg, prefs));

        // 踢人关键词
        root.addView(sLabel(ctx, d, "直接踢人关键词 (命中即踢)"));
        LinearLayout kickLayout = new LinearLayout(ctx);
        kickLayout.setOrientation(LinearLayout.VERTICAL);
        refreshKeywordList(ctx, kickLayout, cfg.kickKeywords, cfg, prefs);
        root.addView(kickLayout);
        root.addView(addKeywordRow(ctx, d, kickLayout, cfg.kickKeywords, cfg, prefs));

        root.addView(candyDivider(ctx, d));

        // 群管增强
        root.addView(sLabel(ctx, d, "群管增强"));
        root.addView(switchRow(ctx, d, "群功能增强 (踢人/禁言/群发/防炸群)", cfg.groupFeaturesEnabled, (v, on) -> {
            cfg.groupFeaturesEnabled = on; cfg.save(prefs); GroupFeatures.setEnabled(on);
        }));

        root.addView(candyDivider(ctx, d));

        // 黑名单
        root.addView(sLabel(ctx, d, "黑名单"));
        root.addView(switchRow(ctx, d, "启用黑名单", cfg.blacklistEnabled, (v, on) -> {
            cfg.blacklistEnabled = on; cfg.save(prefs);
        }));
        root.addView(sLabel(ctx, d, "黑名单 wxid (每行一个)"));
        StringBuilder blText = new StringBuilder();
        for (String w : cfg.blacklistWxids) { blText.append(w).append("\n"); }
        EditText blEdit = new EditText(ctx); blEdit.setText(blText.toString());
        GradientDrawable blBg = new GradientDrawable();
        blBg.setColor(AppColors.inputBg());
        blBg.setCornerRadius((int)(6 * d));
        blBg.setStroke((int)(1.5f * d), AppColors.candyPink());
        blEdit.setBackground(blBg);
        blEdit.setMinLines(3);
        blEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                cfg.blacklistWxids.clear();
                for (String line : s.toString().split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) cfg.blacklistWxids.add(t);
                }
                cfg.save(prefs);
            }
        });
        root.addView(blEdit);

        root.addView(candyDivider(ctx, d));

        // 群邀请 / 退群提示
        root.addView(sLabel(ctx, d, "群事件"));
        root.addView(switchRow(ctx, d, "群邀请自动同意", cfg.groupInviteEnabled, (v, on) -> {
            cfg.groupInviteEnabled = on; cfg.save(prefs);
        }));
        root.addView(editRow(ctx, d, "邀请触发关键词", cfg.groupInviteKeyword, s -> {
            cfg.groupInviteKeyword = s; cfg.save(prefs);
        }));
        root.addView(switchRow(ctx, d, "退群提示", cfg.leftGroupTipEnabled, (v, on) -> {
            cfg.leftGroupTipEnabled = on; cfg.save(prefs);
        }));
        root.addView(editRow(ctx, d, "退群提示语", cfg.leftGroupTipMsg, s -> {
            cfg.leftGroupTipMsg = s; cfg.save(prefs);
        }));

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);
        return sv;
    }

    /** 关键词列表 + 添加按钮 */
    private static LinearLayout addKeywordRow(Context ctx, float d, LinearLayout container,
                                              Set<String> target, ModuleConfig cfg, SharedPreferences prefs) {
        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText input = new EditText(ctx); input.setHint("输入关键词");
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(AppColors.inputBg());
        inputBg.setCornerRadius((int)(6 * d));
        inputBg.setStroke((int)(1.5f * d), AppColors.candyPink());
        input.setBackground(inputBg);
        input.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addRow.addView(input);
        Button addBtn = new Button(ctx); addBtn.setText("添加");
        addBtn.setPaintFlags(addBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        addBtn.setOnClickListener(v -> {
            String kw = input.getText().toString().trim();
            if (!kw.isEmpty()) {
                target.add(kw);
                cfg.save(prefs);
                refreshKeywordList(ctx, container, target, cfg, prefs);
                input.setText("");
            }
        });
        addRow.addView(addBtn);
        return addRow;
    }

    private static void refreshKeywordList(Context ctx, LinearLayout container, Set<String> keywords,
                                           ModuleConfig cfg, SharedPreferences prefs) {
        container.removeAllViews();
        for (String kw : new ArrayList<>(keywords)) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(ctx); tv.setText(kw); tv.setTextSize(14);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(tv);
            Button del = new Button(ctx); del.setText("删除");
            del.setPaintFlags(del.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            del.setOnClickListener(v -> {
                keywords.remove(kw);
                cfg.save(prefs);
                refreshKeywordList(ctx, container, keywords, cfg, prefs);
            });
            row.addView(del);
            container.addView(row);
        }
    }

    private static TextView sLabel(Context ctx, float d, String t) {
        TextView tv = new TextView(ctx); tv.setText(t); tv.setTextSize(18);
        tv.setPadding(0, dp(d, 16), 0, dp(d, 8)); tv.getPaint().setFakeBoldText(true); return tv;
    }

    private static LinearLayout switchRow(Context ctx, float d, String label, boolean checked,
                                          CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(ctx); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 8), 0, dp(d, 8));
        TextView tv = new TextView(ctx); tv.setText(label); tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked); sw.setOnCheckedChangeListener(l);
        row.addView(sw); return row;
    }

    private static LinearLayout editRow(Context ctx, float d, String label, String value, EditCallback cb) {
        LinearLayout row = new LinearLayout(ctx); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(d, 4), 0, dp(d, 4));
        TextView tv = new TextView(ctx); tv.setText(label); tv.setTextSize(14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f));
        row.addView(tv);
        EditText et = new EditText(ctx); et.setText(value); et.setTextSize(14);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(AppColors.inputBg());
        etBg.setCornerRadius(dp(d, 6));
        etBg.setStroke((int)(1.5f * dp(d, 1)), AppColors.candyPink());
        et.setBackground(etBg);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f));
        et.addTextChangedListener(new TextWatcher() {
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

    private static int dp(float density, int dp) { return (int) (dp * density + 0.5f); }
    interface EditCallback { void onChange(String value); }
}
