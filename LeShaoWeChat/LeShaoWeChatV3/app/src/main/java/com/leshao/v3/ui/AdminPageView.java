package com.leshao.v3.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.service.ActivationManager;

public class AdminPageView {

    private static TextView sOutputTv;
    private static Switch[] sFeatureBoxes;
    private static Spinner sLevelSpinner;
    private static EditText sWxidEdit;
    private static int sSelectedHours = 0;

    private static final int[] HOUR_OPTIONS = {1, 12, 24, 72, 168, 720, 2160, 8760, 0};
    private static final String[] HOUR_LABELS = {"1小时", "12小时", "1天", "3天", "7天", "30天", "90天", "365天", "永久有效"};

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        root.addView(sectionLabel(ctx, d, "生成激活码"));
        LinearLayout card1 = makeCard(ctx, d);

        card1.addView(wxidRow(ctx, d));
        card1.addView(itemDivider(ctx, d));
        card1.addView(levelRow(ctx, d));
        card1.addView(itemDivider(ctx, d));
        card1.addView(durationLabel(ctx, d));
        card1.addView(durationBtnGrid(ctx, d));
        root.addView(card1);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, d, "功能开关"));

        LinearLayout selectRow = new LinearLayout(ctx);
        selectRow.setOrientation(LinearLayout.HORIZONTAL);
        selectRow.setPadding(0, 0, 0, (int)(4 * d));

        Button selectAllBtn = new Button(ctx);
        selectAllBtn.setText("全选");
        selectAllBtn.setTextSize(11);
        selectAllBtn.setTextColor(Color.WHITE);
        selectAllBtn.setPadding((int)(10*d), (int)(3*d), (int)(10*d), (int)(3*d));
        GradientDrawable saBg = new GradientDrawable();
        saBg.setCornerRadius((int)(4*d));
        saBg.setColor(AppColors.accent());
        selectAllBtn.setBackground(saBg);
        selectAllBtn.setOnClickListener(v -> { for (Switch sw : sFeatureBoxes) sw.setChecked(true); });
        selectRow.addView(selectAllBtn);

        View btnSpacer = new View(ctx);
        btnSpacer.setLayoutParams(new LinearLayout.LayoutParams((int)(8*d), 0));
        selectRow.addView(btnSpacer);

        Button deselectBtn = new Button(ctx);
        deselectBtn.setText("取消");
        deselectBtn.setTextSize(11);
        deselectBtn.setTextColor(Color.WHITE);
        deselectBtn.setPadding((int)(10*d), (int)(3*d), (int)(10*d), (int)(3*d));
        GradientDrawable dsBg = new GradientDrawable();
        dsBg.setCornerRadius((int)(4*d));
        dsBg.setColor(0xFFE74C3C);
        deselectBtn.setBackground(dsBg);
        deselectBtn.setOnClickListener(v -> { for (Switch sw : sFeatureBoxes) sw.setChecked(false); });
        selectRow.addView(deselectBtn);
        root.addView(selectRow);

        LinearLayout card2 = makeCard(ctx, d);
        sFeatureBoxes = new Switch[ActivationManager.FEATURE_NAMES.length];
        for (int i = 0; i < ActivationManager.FEATURE_NAMES.length; i++) {
            if (i > 0) card2.addView(itemDivider(ctx, d));
            card2.addView(featureSwitchNew(ctx, d, ActivationManager.FEATURE_NAMES[i], true, i, sFeatureBoxes));
        }
        root.addView(card2);

        root.addView(spacerV(ctx, d, 12));
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button btnGen = new Button(ctx);
        btnGen.setText("生成激活码");
        btnGen.setTextSize(12);
        btnGen.setTextColor(Color.WHITE);
        btnGen.setPadding((int)(20*d), (int)(8*d), (int)(20*d), (int)(8*d));
        GradientDrawable genBg = new GradientDrawable();
        genBg.setCornerRadius((int)(6*d));
        genBg.setColor(AppColors.accent());
        btnGen.setBackground(genBg);
        btnGen.setOnClickListener(v -> onGenerate(ctx));
        btnRow.addView(btnGen);

        View s2 = new View(ctx);
        s2.setLayoutParams(new LinearLayout.LayoutParams((int)(16*d), 0));
        btnRow.addView(s2);

        Button btnCopy = new Button(ctx);
        btnCopy.setText("复制");
        btnCopy.setTextSize(12);
        btnCopy.setTextColor(Color.WHITE);
        btnCopy.setPadding((int)(20*d), (int)(8*d), (int)(20*d), (int)(8*d));
        GradientDrawable cpBg = new GradientDrawable();
        cpBg.setCornerRadius((int)(6*d));
        cpBg.setColor(0xFF607D8B);
        btnCopy.setBackground(cpBg);
        btnCopy.setOnClickListener(v -> onCopy(ctx));
        btnRow.addView(btnCopy);
        root.addView(btnRow);

        root.addView(spacerV(ctx, d, 12));
        root.addView(sectionLabel(ctx, d, "生成的激活码"));
        LinearLayout card3 = makeCard(ctx, d);
        sOutputTv = new TextView(ctx);
        sOutputTv.setText("点击「生成激活码」生成");
        sOutputTv.setTextSize(13);
        sOutputTv.setTextColor(AppColors.text2());
        sOutputTv.setGravity(Gravity.CENTER);
        sOutputTv.setPadding((int)(14*d), (int)(16*d), (int)(14*d), (int)(16*d));
        sOutputTv.setTextIsSelectable(true);
        card3.addView(sOutputTv);
        root.addView(card3);

        sv.addView(root);
        return sv;
    }

    private static void onGenerate(Context ctx) {
        String wxid = sWxidEdit.getText().toString().trim();
        if (wxid.isEmpty()) { toast(ctx, "请输入目标 wxid"); return; }

        int levelIndex = sLevelSpinner.getSelectedItemPosition();
        int featureMask = 0;
        for (int i = 0; i < sFeatureBoxes.length; i++) {
            if (sFeatureBoxes[i].isChecked()) featureMask |= (1 << i);
        }

        String code = ActivationManager.generateCode(wxid, levelIndex, sSelectedHours, featureMask);
        if (code.isEmpty()) { toast(ctx, "生成失败"); return; }

        String lvlName = sLevelSpinner.getSelectedItem().toString();
        String durStr = sSelectedHours == 0 ? "永久有效" : sSelectedHours + "小时";
        StringBuilder info = new StringBuilder();
        info.append(code).append("\n\n");
        info.append("绑定wxid: ").append(wxid).append("\n");
        info.append("等级: ").append(lvlName).append(" (index=").append(levelIndex).append(")\n");
        info.append("有效期: ").append(durStr).append("\n");
        info.append("功能数: ").append(Integer.bitCount(featureMask)).append("/").append(sFeatureBoxes.length).append("\n");
        info.append("Feature Mask: 0x").append(Integer.toHexString(featureMask));
        sOutputTv.setText(info.toString());
        sOutputTv.setTextColor(AppColors.text1());
    }

    private static void onCopy(Context ctx) {
        String text = sOutputTv.getText().toString();
        if (text.isEmpty() || text.startsWith("点击")) { toast(ctx, "请先生成激活码"); return; }
        int nl = text.indexOf('\n');
        String code = nl > 0 ? text.substring(0, nl) : text;
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("activation_code", code));
        toast(ctx, "已复制到剪贴板");
    }

    private static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    // ===== 新组件 =====

    private static View wxidRow(Context ctx, float d) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams((int)(72*d), -2));
        TextView label = new TextView(ctx);
        label.setText("目标 wxid");
        label.setTextSize(15);
        label.setTextColor(AppColors.text1());
        label.setTypeface(null, Typeface.BOLD);
        textCol.addView(label);
        row.addView(textCol);

        sWxidEdit = new EditText(ctx);
        sWxidEdit.setHint("wxid_xxx");
        sWxidEdit.setTextSize(13);
        sWxidEdit.setTextColor(AppColors.text1());
        sWxidEdit.setHintTextColor(AppColors.text2());
        sWxidEdit.setSingleLine(true);
        sWxidEdit.setPadding((int)(8*d), (int)(6*d), (int)(8*d), (int)(6*d));
        sWxidEdit.setBackgroundColor(AppColors.card());
        sWxidEdit.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(sWxidEdit);

        TextView hint = new TextView(ctx);
        hint.setText("必填");
        hint.setTextSize(10);
        hint.setTextColor(AppColors.text2());
        hint.setPadding((int)(6*d), 0, 0, 0);
        row.addView(hint);
        return row;
    }

    private static View levelRow(Context ctx, float d) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams((int)(72*d), -2));
        TextView label = new TextView(ctx);
        label.setText("等级名称");
        label.setTextSize(15);
        label.setTextColor(AppColors.text1());
        label.setTypeface(null, Typeface.BOLD);
        textCol.addView(label);
        row.addView(textCol);

        sLevelSpinner = new Spinner(ctx);
        String[] options = {"体验会员", "月度会员", "季度会员", "年度会员", "终身会员"};
        sLevelSpinner.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, options));
        sLevelSpinner.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        sLevelSpinner.setSelection(4);
        row.addView(sLevelSpinner);
        return row;
    }

    private static View durationLabel(Context ctx, float d) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(4*d));
        row.setBackgroundColor(AppColors.whiteCard());
        TextView label = new TextView(ctx);
        label.setText("会员时长");
        label.setTextSize(15);
        label.setTextColor(AppColors.text1());
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, 0, 0, (int)(4*d));
        row.addView(label);
        return row;
    }

    private static View durationBtnGrid(Context ctx, float d) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int)(14*d), (int)(2*d), (int)(14*d), (int)(10*d));
        container.setBackgroundColor(AppColors.whiteCard());

        sSelectedHours = 8760; // default: 365天(永久)

        for (int rowIdx = 0; rowIdx < 3; rowIdx++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int)(3*d), 0, 0);

            for (int col = 0; col < 3; col++) {
                int idx = rowIdx * 3 + col;
                if (idx >= HOUR_OPTIONS.length) break;
                int hours = HOUR_OPTIONS[idx];
                Button btn = new Button(ctx);
                btn.setText(HOUR_LABELS[idx]);
                btn.setTextSize(10);
                btn.setPadding((int)(6*d), (int)(4*d), (int)(6*d), (int)(4*d));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1.0f);
                if (col < 2) blp.setMargins(0, 0, (int)(4*d), 0);
                btn.setLayoutParams(blp);

                final int hVal = hours;
                updateDurationBtnStyle(btn, hours == sSelectedHours);
                btn.setOnClickListener(v -> {
                    sSelectedHours = hVal;
                    updateAllDurationButtons(container, (int)(4*d));
                });
                row.addView(btn);
            }
            container.addView(row);
        }
        return container;
    }

    private static void updateAllDurationButtons(LinearLayout container, float d) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View btn = row.getChildAt(j);
                    if (btn instanceof Button) {
                        String label = ((Button) btn).getText().toString();
                        for (int k = 0; k < HOUR_LABELS.length; k++) {
                            if (HOUR_LABELS[k].equals(label)) {
                                updateDurationBtnStyle((Button) btn, HOUR_OPTIONS[k] == sSelectedHours);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private static void updateDurationBtnStyle(Button btn, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius((int)(4));
        bg.setColor(selected ? AppColors.accent() : AppColors.card());
        btn.setBackground(bg);
        btn.setTextColor(selected ? Color.WHITE : AppColors.text1());
    }

    private static View featureSwitchNew(Context ctx, float d, String title, boolean checked,
                                          int idx, Switch[] boxes) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text1());
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tv.setLayoutParams(tvlp);
        row.addView(tv);

        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 0.1f));
        row.addView(spacer);

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        row.addView(sw);

        boxes[idx] = sw;
        return row;
    }

    // ===== 工厂方法 =====

    private static TextView sectionLabel(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8*d));
        return tv;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2*d), (int)(2*d), (int)(2*d), (int)(2*d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static View itemDivider(Context ctx, float d) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        v.setBackgroundColor(AppColors.divider());
        return v;
    }

    private static View spacerV(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal*d)));
        return v;
    }
}
