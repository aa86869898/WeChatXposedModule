package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.hook.ChatGroupHook;
import com.leshao.v3.hook.model.LabelInfo;
import com.leshao.v3.ui.widgets.M3Page;

import java.util.List;

/**
 * 聊天分组页：仅保留「分组列表」与聊天列表顶部分组标签栏配套的分组管理。
 */
public class ChatGroupPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(AppColors.SPACE_MD_DP * d), (int)(6 * d),
                (int)(AppColors.SPACE_MD_DP * d), (int)(8 * d));

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, (int)(8 * d), 0, 0);
        root.addView(content);

        // v1145: 页面顶部统一分区标题
        content.addView(M3Page.section(ctx, "聊天分组",
                "标签/分组管理，聊天列表顶部同步显示分组栏"));

        buildLabelList(ctx, parentAct, d, content);
        return root;
    }

    private static void refreshLabelList(Context ctx, Activity parentAct, float d, LinearLayout content) {
        content.removeAllViews();
        buildLabelList(ctx, parentAct, d, content);
    }

    private static void buildLabelList(Context ctx, Activity parentAct, float d, LinearLayout content) {
        LinearLayout card = makeCard(ctx, d);
        TextView header = new TextView(ctx);
        header.setText("分组管理");
        header.setTextSize(16); header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding((int)(12*d), (int)(12*d), (int)(12*d), (int)(4*d));
        card.addView(header);

        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding((int)(12*d), (int)(4*d), (int)(12*d), (int)(10*d));

        EditText searchEt = new EditText(ctx);
        searchEt.setHint("搜索分组...");
        searchEt.setTextSize(12);
        searchEt.setSingleLine(true);
        searchEt.setPadding((int)(8*d), (int)(6*d), (int)(8*d), (int)(6*d));
        searchEt.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        searchEt.setBackground(CandyUi.inputBg(ctx));
        int sp = (int)(10*d);
        searchEt.setPadding(sp, sp, sp, sp);
        searchEt.setHintTextColor(AppColors.text3());
        actionRow.addView(searchEt);

        TextView createBtn = new TextView(ctx);
        createBtn.setText("+新建");
        createBtn.setTextSize(12); createBtn.setTextColor(AppColors.accent());
        createBtn.setTypeface(null, Typeface.BOLD);
        createBtn.setPadding((int)(10*d), 0, 0, 0);
        CandyUi.ripple(createBtn, AppColors.SHAPE_FULL_DP);
        createBtn.setOnClickListener(v -> showCreateLabelDialog(ctx, parentAct, d, () -> refreshLabelList(ctx, parentAct, d, content)));
        actionRow.addView(createBtn);

        TextView refreshBtn = new TextView(ctx);
        refreshBtn.setText("刷新");
        refreshBtn.setTextSize(11); refreshBtn.setTextColor(AppColors.text2());
        refreshBtn.setPadding((int)(8*d), 0, 0, 0);
        CandyUi.ripple(refreshBtn, AppColors.SHAPE_FULL_DP);
        refreshBtn.setOnClickListener(v -> {
            ChatGroupHook.refreshCache();
            Toast.makeText(parentAct, "已刷新", Toast.LENGTH_SHORT).show();
        });
        actionRow.addView(refreshBtn);

        card.addView(actionRow);

        // Search results
        LinearLayout listRoot = new LinearLayout(ctx);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        card.addView(listRoot);
        content.addView(card);

        searchEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int cnt, int aft) {}
            @Override public void onTextChanged(CharSequence s, int st, int bef, int cnt) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                listRoot.removeAllViews();
                String q = s.toString().trim().toLowerCase();
                List<LabelInfo> labels = q.isEmpty() ? ChatGroupHook.getAllLabels() : ChatGroupHook.searchLabels(q);
                if (labels.isEmpty()) {
                    TextView empty = new TextView(ctx);
                    empty.setText("暂无分组");
                    empty.setTextSize(12); empty.setTextColor(AppColors.text2());
                    empty.setPadding((int)(12*d), (int)(16*d), (int)(12*d), (int)(16*d));
                    empty.setGravity(Gravity.CENTER);
                    listRoot.addView(empty);
                } else {
                    for (int i = 0; i < labels.size(); i++) {
                        Runnable ref = () -> refreshLabelList(ctx, parentAct, d, content);
                        listRoot.addView(buildLabelRow(ctx, parentAct, d, labels.get(i), ref));
                    }
                }
            }
        });
        searchEt.setText("");
    }

    private static View buildLabelRow(Context ctx, Activity parentAct, float d, LabelInfo label, Runnable refresh) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(12*d), (int)(10*d), (int)(12*d), (int)(10*d));
        row.setBackground(CandyUi.rowPressBg(ctx));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tvName = new TextView(ctx);
        tvName.setText(label.labelName);
        tvName.setTextSize(14); tvName.setTextColor(AppColors.text1());
        tvName.setTypeface(null, Typeface.BOLD);
        textCol.addView(tvName);

        TextView tvCount = new TextView(ctx);
        boolean builtIn = label.labelId == ChatGroupHook.LABEL_ID_GROUP || label.labelId == ChatGroupHook.LABEL_ID_FRIEND || label.labelId == ChatGroupHook.LABEL_ID_SERVICE;
        tvCount.setText(builtIn ? "内置分组" : (label.contacts.size() + " 位联系人" + (label.isTemporary ? " | 临时" : "")));
        tvCount.setTextSize(12); tvCount.setTextColor(AppColors.text2());
        tvCount.setPadding(0, (int)(2*d), 0, 0);
        textCol.addView(tvCount);
        row.addView(textCol);

        TextView editBtn = new TextView(ctx);
        editBtn.setText("编辑"); editBtn.setTextSize(11);
        editBtn.setTextColor(AppColors.accent());
        editBtn.setPadding((int)(6*d), (int)(6*d), (int)(6*d), (int)(6*d));
        CandyUi.ripple(editBtn, AppColors.SHAPE_FULL_DP);
        editBtn.setOnClickListener(v -> showRenameLabelDialog(ctx, parentAct, d, String.valueOf(label.labelId), label.labelName, refresh));
        row.addView(editBtn);

        TextView delBtn = new TextView(ctx);
        delBtn.setText("删除"); delBtn.setTextSize(11);
        delBtn.setTextColor(AppColors.error());
        delBtn.setPadding((int)(6*d), (int)(6*d), (int)(6*d), (int)(6*d));
        CandyUi.ripple(delBtn, AppColors.SHAPE_FULL_DP);
        delBtn.setOnClickListener(v -> showDeleteLabelDialog(ctx, parentAct, d, String.valueOf(label.labelId), label.labelName, refresh));
        if (label.labelId != ChatGroupHook.LABEL_ID_GROUP && label.labelId != ChatGroupHook.LABEL_ID_FRIEND && label.labelId != ChatGroupHook.LABEL_ID_SERVICE) {
            row.addView(delBtn);
        }

        row.setOnClickListener(v -> showLabelMembers(ctx, parentAct, d, label));
        return row;
    }

    // ==================== Dialogs ====================
    private static void showCreateLabelDialog(Context ctx, Activity parentAct, float d, Runnable onDone) {
        EditText et = makeEditText(ctx, d, "输入分组名称");
        showInputDialog(ctx, parentAct, d, "新建分组", et, () -> {
            String name = et.getText().toString().trim();
            if (name.isEmpty()) { Toast.makeText(parentAct, "名称不能为空", Toast.LENGTH_SHORT).show(); return false; }
            LabelInfo l = ChatGroupHook.createLabel(name);
            if (l != null) { Toast.makeText(parentAct, "创建成功", Toast.LENGTH_SHORT).show(); return true; }
            else { Toast.makeText(parentAct, "创建失败", Toast.LENGTH_SHORT).show(); return false; }
        }, onDone);
    }

    private static void showRenameLabelDialog(Context ctx, Activity parentAct, float d, String labelId, String oldName, Runnable onDone) {
        EditText et = makeEditText(ctx, d, "");
        et.setText(oldName);
        showInputDialog(ctx, parentAct, d, "重命名分组", et, () -> {
            String name = et.getText().toString().trim();
            if (name.isEmpty()) { Toast.makeText(parentAct, "名称不能为空", Toast.LENGTH_SHORT).show(); return false; }
            return ChatGroupHook.renameLabel(labelId, name);
        }, onDone);
    }

    private static void showDeleteLabelDialog(Context ctx, Activity parentAct, float d, String labelId, String labelName, Runnable onDone) {
        showConfirmDialog(ctx, parentAct, d, "确认删除", "确定要删除分组 \"" + labelName + "\" 吗？", "删除", AppColors.error(), () -> {
            if (ChatGroupHook.deleteLabel(labelId)) { Toast.makeText(parentAct, "已删除", Toast.LENGTH_SHORT).show(); if (onDone != null) onDone.run(); }
            else Toast.makeText(parentAct, "删除失败", Toast.LENGTH_SHORT).show();
        });
    }

    private static void showLabelMembers(Context ctx, Activity parentAct, float d, LabelInfo label) {
        buildMemberDialog(ctx, parentAct, d, label);
    }

    private static void buildMemberDialog(Context ctx, Activity parentAct, float d, LabelInfo label) {
        int dlgTheme = AppColors.isDarkMode() ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();
        LinearLayout dlgRoot = new LinearLayout(ctx);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setPadding((int)(12*d), (int)(12*d), (int)(12*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.dialogBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView title = new TextView(ctx);
        title.setText("分组: " + label.labelName);
        title.setTextSize(16); title.setTextColor(AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, (int)(4*d));
        dlgRoot.addView(title);

        TextView countText = new TextView(ctx);
        countText.setText("共 " + label.contacts.size() + " 位联系人");
        countText.setTextSize(12); countText.setTextColor(AppColors.text2());
        countText.setPadding(0, 0, 0, (int)(10*d));
        dlgRoot.addView(countText);

        if (label.contacts.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无联系人");
            empty.setTextSize(12); empty.setTextColor(AppColors.text2());
            empty.setPadding(0, 0, 0, (int)(8*d));
            dlgRoot.addView(empty);
        } else {
            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            LinearLayout ml = new LinearLayout(ctx);
            ml.setOrientation(LinearLayout.VERTICAL);
            for (String m : label.contacts) {
                TextView mt = new TextView(ctx);
                mt.setText(m);
                mt.setTextSize(11); mt.setTextColor(AppColors.text1());
                mt.setPadding(0, (int)(4*d), 0, (int)(4*d));
                ml.addView(mt);
            }
            sv.addView(ml);
            dlgRoot.addView(sv);
        }

        TextView close = new TextView(ctx);
        close.setText("关闭");
        close.setTextSize(14); close.setTextColor(AppColors.text2());
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, (int)(8*d), 0, 0);
        CandyUi.ripple(close, AppColors.SHAPE_FULL_DP);
        close.setOnClickListener(v2 -> dialog.dismiss());
        dlgRoot.addView(close);

        dialog.setView(dlgRoot);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    // ==================== UI Helpers ====================
    private static void showInputDialog(Context ctx, Activity parentAct, float d, String title, EditText et, java.util.function.BooleanSupplier onConfirm, Runnable onDone) {
        int dlgTheme = AppColors.isDarkMode() ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();
        LinearLayout dlgRoot = new LinearLayout(ctx);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setPadding((int)(12*d), (int)(12*d), (int)(12*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.dialogBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView dlgTitle = new TextView(ctx); dlgTitle.setText(title); dlgTitle.setTextSize(16); dlgTitle.setTextColor(AppColors.text1()); dlgTitle.setTypeface(null, Typeface.BOLD); dlgTitle.setPadding(0,0,0,(int)(10*d)); dlgRoot.addView(dlgTitle);
        dlgRoot.addView(et);
        dlgRoot.addView(spacerV(ctx, d, 10));

        LinearLayout btnRow = new LinearLayout(ctx); btnRow.setOrientation(LinearLayout.HORIZONTAL); btnRow.setGravity(Gravity.CENTER);
        TextView cancel = new TextView(ctx); cancel.setText("取消"); cancel.setTextSize(14); cancel.setTextColor(AppColors.text2()); cancel.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d)); CandyUi.ripple(cancel, AppColors.SHAPE_FULL_DP); cancel.setOnClickListener(v2->dialog.dismiss()); btnRow.addView(cancel);
        TextView confirm = new TextView(ctx); confirm.setText("确认"); confirm.setTextSize(14); confirm.setTextColor(AppColors.accent()); confirm.setTypeface(null, Typeface.BOLD); confirm.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d));
        CandyUi.ripple(confirm, AppColors.SHAPE_FULL_DP);
        confirm.setOnClickListener(v2 -> { if (onConfirm.getAsBoolean()) { dialog.dismiss(); if (onDone != null) onDone.run(); } });
        btnRow.addView(confirm);
        dlgRoot.addView(btnRow);

        dialog.setView(dlgRoot);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    private static void showConfirmDialog(Context ctx, Activity parentAct, float d, String title, String msg, String btnText, int btnColor, Runnable onConfirm) {
        int dlgTheme = AppColors.isDarkMode() ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();
        LinearLayout dlgRoot = new LinearLayout(ctx);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setPadding((int)(12*d), (int)(12*d), (int)(12*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.dialogBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView dlgTitle = new TextView(ctx); dlgTitle.setText(title); dlgTitle.setTextSize(16); dlgTitle.setTextColor(AppColors.text1()); dlgTitle.setTypeface(null, Typeface.BOLD); dlgTitle.setPadding(0,0,0,(int)(4*d)); dlgRoot.addView(dlgTitle);
        TextView msgTv = new TextView(ctx); msgTv.setText(msg); msgTv.setTextSize(13); msgTv.setTextColor(AppColors.text2()); msgTv.setPadding(0,0,0,(int)(10*d)); dlgRoot.addView(msgTv);

        LinearLayout btnRow = new LinearLayout(ctx); btnRow.setOrientation(LinearLayout.HORIZONTAL); btnRow.setGravity(Gravity.CENTER);
        TextView cancel = new TextView(ctx); cancel.setText("取消"); cancel.setTextSize(14); cancel.setTextColor(AppColors.text2()); cancel.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d)); CandyUi.ripple(cancel, AppColors.SHAPE_FULL_DP); cancel.setOnClickListener(v2->dialog.dismiss()); btnRow.addView(cancel);
        TextView confirm = new TextView(ctx); confirm.setText(btnText); confirm.setTextSize(14); confirm.setTextColor(btnColor); confirm.setTypeface(null, Typeface.BOLD); confirm.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d));
        CandyUi.ripple(confirm, AppColors.SHAPE_FULL_DP);
        confirm.setOnClickListener(v2 -> { onConfirm.run(); dialog.dismiss(); });
        btnRow.addView(confirm);
        dlgRoot.addView(btnRow);

        dialog.setView(dlgRoot);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    private static EditText makeEditText(Context ctx, float d, String hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint); et.setTextSize(14);
        et.setPadding((int)(12*d), (int)(10*d), (int)(12*d), (int)(10*d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(AppColors.inputBg());
        bg.setCornerRadius((int)(AppColors.SHAPE_INPUT_DP*d));
        bg.setStroke((int)(1.5f*d), AppColors.outlineVariant());
        et.setBackground(bg);
        return et;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(CandyUi.cardBg(ctx));
        card.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, (int)(13*d));
        card.setLayoutParams(lp);
        return card;
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp*d)));
        return v;
    }
}
