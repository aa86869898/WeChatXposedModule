package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import com.leshao.v3.hook.*;
import com.leshao.v3.hook.model.AutoRule;
import com.leshao.v3.hook.model.LabelInfo;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.SegmentedControl;

import java.io.File;
import java.util.*;

public class ChatGroupPageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(16 * d), (int)(12 * d), (int)(16 * d), (int)(16 * d));

        // Tab bar
        LinearLayout tabBar = new LinearLayout(ctx);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setBackgroundColor(AppColors.card());
        tabBar.setPadding((int)(4 * d), (int)(4 * d), (int)(4 * d), (int)(4 * d));
        GradientDrawable tabBg = new GradientDrawable();
        tabBg.setColor(AppColors.card());
        tabBg.setCornerRadius((int)(8 * d));
        tabBar.setBackground(tabBg);
        InsetsUtil.clipRounded(tabBar);

        String[] tabs = {"分组列表", "自动规则", "批量操作", "备份恢复"};
        final LinearLayout[] contentArea = {null};
        Runnable[] rebuildContent = {null};

        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            TextView tab = new TextView(ctx);
            tab.setText(tabs[i]);
            tab.setTextSize(12);
            tab.setTextColor(i == 0 ? AppColors.accent() : AppColors.text2());
            tab.setTypeface(null, i == 0 ? Typeface.BOLD : Typeface.NORMAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding((int)(8 * d), (int)(6 * d), (int)(8 * d), (int)(6 * d));
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            tab.setOnClickListener(v -> {
                for (int j = 0; j < tabBar.getChildCount(); j++) {
                    TextView child = (TextView) tabBar.getChildAt(j);
                    child.setTextColor(j == idx ? AppColors.accent() : AppColors.text2());
                    child.setTypeface(null, j == idx ? Typeface.BOLD : Typeface.NORMAL);
                }
                buildContent(ctx, parentAct, d, idx, contentArea, rebuildContent);
            });
            tabBar.addView(tab);
        }
        root.addView(tabBar);

        root.addView(candyDivider(ctx, d));

        // Content area
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, (int)(8 * d), 0, 0);
        root.addView(content);
        contentArea[0] = content;

        buildContent(ctx, parentAct, d, 0, contentArea, rebuildContent);

        return root;
    }

    private static void buildContent(Context ctx, Activity parentAct, float d, int tab,
                                      LinearLayout[] contentArea, Runnable[] rebuildContent) {
        LinearLayout content = contentArea[0];
        content.removeAllViews();

        switch (tab) {
            case 0: buildLabelList(ctx, parentAct, d, content); break;
            case 1: buildAutoRules(ctx, parentAct, d, content); break;
            case 2: buildBatchOps(ctx, parentAct, d, content); break;
            case 3: buildBackupRestore(ctx, parentAct, d, content); break;
        }
    }

    // ==================== Tab 1: 分组列表 ====================
    private static void refreshLabelList(Context ctx, Activity parentAct, float d, LinearLayout content) {
        content.removeAllViews();
        buildLabelList(ctx, parentAct, d, content);
    }

    private static void buildLabelList(Context ctx, Activity parentAct, float d, LinearLayout content) {
        LinearLayout card = makeCard(ctx, d);
        TextView header = new TextView(ctx);
        header.setText("分组管理");
        header.setTextSize(15); header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(4*d));
        card.addView(header);

        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding((int)(14*d), (int)(4*d), (int)(14*d), (int)(10*d));

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
        createBtn.setOnClickListener(v -> showCreateLabelDialog(ctx, parentAct, d, () -> refreshLabelList(ctx, parentAct, d, content)));
        actionRow.addView(createBtn);

        TextView refreshBtn = new TextView(ctx);
        refreshBtn.setText("刷新");
        refreshBtn.setTextSize(11); refreshBtn.setTextColor(AppColors.text2());
        refreshBtn.setPadding((int)(8*d), 0, 0, 0);
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
            @Override public void afterTextChanged(android.text.Editable s) {
                listRoot.removeAllViews();
                String q = s.toString().trim().toLowerCase();
                List<LabelInfo> labels = q.isEmpty() ? ChatGroupHook.getAllLabels() : ChatGroupHook.searchLabels(q);
                if (labels.isEmpty()) {
                    TextView empty = new TextView(ctx);
                    empty.setText("暂无分组");
                    empty.setTextSize(12); empty.setTextColor(AppColors.text2());
                    empty.setPadding((int)(14*d), (int)(16*d), (int)(14*d), (int)(16*d));
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
        row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
        row.setBackground(CandyUi.rowPressBg(ctx));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tvName = new TextView(ctx);
        tvName.setText(label.labelName);
        tvName.setTextSize(13); tvName.setTextColor(AppColors.text1());
        tvName.setTypeface(null, Typeface.BOLD);
        textCol.addView(tvName);

        TextView tvCount = new TextView(ctx);
        boolean builtIn = label.labelId == ChatGroupHook.LABEL_ID_GROUP || label.labelId == ChatGroupHook.LABEL_ID_FRIEND || label.labelId == ChatGroupHook.LABEL_ID_SERVICE;
        tvCount.setText(builtIn ? "内置分组" : (label.contacts.size() + " 位联系人" + (label.isTemporary ? " | 临时" : "")));
        tvCount.setTextSize(11); tvCount.setTextColor(AppColors.text2());
        tvCount.setPadding(0, (int)(2*d), 0, 0);
        textCol.addView(tvCount);
        row.addView(textCol);

        TextView editBtn = new TextView(ctx);
        editBtn.setText("编辑"); editBtn.setTextSize(11);
        editBtn.setTextColor(AppColors.accent());
        editBtn.setPadding((int)(6*d), (int)(6*d), (int)(6*d), (int)(6*d));
        editBtn.setOnClickListener(v -> showRenameLabelDialog(ctx, parentAct, d, String.valueOf(label.labelId), label.labelName, refresh));
        row.addView(editBtn);

        TextView delBtn = new TextView(ctx);
        delBtn.setText("删除"); delBtn.setTextSize(11);
        delBtn.setTextColor(AppColors.error());
        delBtn.setPadding((int)(6*d), (int)(6*d), (int)(6*d), (int)(6*d));
        delBtn.setOnClickListener(v -> showDeleteLabelDialog(ctx, parentAct, d, String.valueOf(label.labelId), label.labelName, refresh));
        if (label.labelId != ChatGroupHook.LABEL_ID_GROUP && label.labelId != ChatGroupHook.LABEL_ID_FRIEND && label.labelId != ChatGroupHook.LABEL_ID_SERVICE) {
            row.addView(delBtn);
        }

        row.setOnClickListener(v -> showLabelMembers(ctx, parentAct, d, label));
        return row;
    }

    // ==================== Tab 2: 自动规则 ====================
    private static void buildAutoRules(Context ctx, Activity parentAct, float d, LinearLayout content) {
        LinearLayout card = makeCard(ctx, d);
        card.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));

        TextView header = new TextView(ctx);
        header.setText("自动分组规则");
        header.setTextSize(15); header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, (int)(4*d));
        card.addView(header);

        TextView desc = new TextView(ctx);
        desc.setText("根据备注/昵称/微信号自动为联系人打标签");
        desc.setTextSize(11); desc.setTextColor(AppColors.text2());
        desc.setPadding(0, 0, 0, (int)(8*d));
        card.addView(desc);

        // Enable toggle
        boolean autoOn = GroupConfigManager.isAutoGroupEnabled();
        LinearLayout toggleRow = switchRow(ctx, d, "启用自动分组", autoOn, (v, on) -> {
            GroupConfigManager.setAutoGroupEnabled(on);
            if (on) { AutoGroupEngine.start(); AutoGroupEngine.subscribeToEvents(); }
            Toast.makeText(parentAct, "自动分组已" + (on ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
        });
        card.addView(toggleRow);

        // Rule list
        LinearLayout ruleList = new LinearLayout(ctx);
        ruleList.setOrientation(LinearLayout.VERTICAL);
        card.addView(ruleList);

        List<AutoRule> rules = AutoGroupEngine.getRules();
        if (rules.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无规则，点击下方添加");
            empty.setTextSize(12); empty.setTextColor(AppColors.text2());
            empty.setPadding(0, (int)(8*d), 0, (int)(8*d));
            ruleList.addView(empty);
        } else {
            for (AutoRule r : rules) {
                LinearLayout rRow = new LinearLayout(ctx);
                rRow.setOrientation(LinearLayout.VERTICAL);
                rRow.setPadding(0, (int)(6*d), 0, (int)(4*d));

                TextView rName = new TextView(ctx);
                rName.setText(r.ruleName + " [" + typeLabel(r.matchType) + "='" + r.matchValue + "' -> " + getLabelName(r.targetLabelId) + "]");
                rName.setTextSize(11); rName.setTextColor(r.enabled ? AppColors.text1() : AppColors.text2());
                rRow.addView(rName);

                TextView rInfo = new TextView(ctx);
                rInfo.setText("命中 " + r.matchCount + " 次" + (r.enabled ? "" : " (已禁用)"));
                rInfo.setTextSize(10); rInfo.setTextColor(AppColors.text3());
                rRow.addView(rInfo);

                LinearLayout rActions = new LinearLayout(ctx);
                rActions.setOrientation(LinearLayout.HORIZONTAL);
                TextView rToggle = new TextView(ctx);
                rToggle.setText(r.enabled ? "禁用" : "启用");
                rToggle.setTextSize(10); rToggle.setTextColor(AppColors.accent());
                rToggle.setPadding(0, (int)(2*d), (int)(12*d), 0);
                final AutoRule fr = r;
                rToggle.setOnClickListener(v -> {
                    AutoGroupEngine.setRuleEnabled(fr.ruleId, !fr.enabled);
                    buildAutoRules(ctx, parentAct, d, content);
                });
                rActions.addView(rToggle);

                TextView rDel = new TextView(ctx);
                rDel.setText("删除");
                rDel.setTextSize(10); rDel.setTextColor(0xFFE53935);
                rDel.setPadding(0, (int)(2*d), 0, 0);
                rDel.setOnClickListener(v -> {
                    AutoGroupEngine.removeRule(fr.ruleId);
                    buildAutoRules(ctx, parentAct, d, content);
                });
                rActions.addView(rDel);
                rRow.addView(rActions);
                ruleList.addView(rRow);
            }
        }

        card.addView(candyDivider(ctx, d));

        // Add rule button
        TextView addRuleBtn = new TextView(ctx);
        addRuleBtn.setText("+ 添加规则");
        addRuleBtn.setTextSize(12); addRuleBtn.setTextColor(AppColors.accent());
        addRuleBtn.setTypeface(null, Typeface.BOLD);
        addRuleBtn.setPadding(0, (int)(8*d), 0, 0);
        addRuleBtn.setOnClickListener(v -> showAddRuleDialog(ctx, parentAct, d, content));
        card.addView(addRuleBtn);

        content.addView(card);
    }

    private static String typeLabel(AutoRule.MatchType t) {
        switch (t) {
            case NICKNAME_CONTAINS: return "昵称";
            case REMARK_CONTAINS: return "备注";
            case USERNAME_MATCHES: return "微信号";
            case LABEL_NAME_MATCHES: return "已有标签";
            default: return t.name();
        }
    }

    private static String getLabelName(int labelId) {
        LabelInfo l = ChatGroupHook.getLabelById(String.valueOf(labelId));
        return l != null ? l.labelName : "未知";
    }

    // ==================== Tab 3: 批量操作 ====================
    private static void buildBatchOps(Context ctx, Activity parentAct, float d, LinearLayout content) {
        LinearLayout card = makeCard(ctx, d);
        card.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));

        TextView header = new TextView(ctx);
        header.setText("批量操作");
        header.setTextSize(15); header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, (int)(8*d));
        card.addView(header);

        String[] ops = {"合并两个标签", "清空标签联系人", "查看统计信息"};
        for (String op : ops) {
            TextView btn = new TextView(ctx);
            btn.setText(op);
            btn.setTextSize(13); btn.setTextColor(AppColors.accent());
            btn.setPadding(0, (int)(8*d), 0, (int)(8*d));
            btn.setOnClickListener(v -> {
                if (op.equals("合并两个标签")) showMergeDialog(ctx, parentAct, d);
                else if (op.equals("清空标签联系人")) showClearLabelDialog(ctx, parentAct, d);
                else showStatsDialog(ctx, parentAct, d);
            });
            card.addView(btn);
            card.addView(candyDivider(ctx, d));
        }
        content.addView(card);
    }

    // ==================== Tab 4: 备份恢复 ====================
    private static void buildBackupRestore(Context ctx, Activity parentAct, float d, LinearLayout content) {
        LinearLayout card = makeCard(ctx, d);
        card.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));

        TextView header = new TextView(ctx);
        header.setText("备份与恢复");
        header.setTextSize(15); header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, (int)(8*d));
        card.addView(header);

        TextView exportBtn = new TextView(ctx);
        exportBtn.setText("导出备份到JSON");
        exportBtn.setTextSize(13); exportBtn.setTextColor(AppColors.accent());
        exportBtn.setPadding(0, (int)(8*d), 0, (int)(4*d));
        exportBtn.setOnClickListener(v -> {
            String path = LabelBackup.exportToJson(ctx);
            if (path != null) Toast.makeText(parentAct, "已导出: " + path, Toast.LENGTH_LONG).show();
            else Toast.makeText(parentAct, "导出失败", Toast.LENGTH_SHORT).show();
        });
        card.addView(exportBtn);

        List<File> backups = LabelBackup.listBackups();
        if (!backups.isEmpty()) {
            TextView listTitle = new TextView(ctx);
            listTitle.setText("历史备份 (" + backups.size() + " 个):");
            listTitle.setTextSize(11); listTitle.setTextColor(AppColors.text2());
            listTitle.setPadding(0, (int)(8*d), 0, (int)(4*d));
            card.addView(listTitle);

            ScrollView sv = new ScrollView(ctx);
            LinearLayout backupList = new LinearLayout(ctx);
            backupList.setOrientation(LinearLayout.VERTICAL);
            int maxShow = Math.min(backups.size(), 5);
            for (int i = 0; i < maxShow; i++) {
                final File f = backups.get(i);
                LinearLayout br = new LinearLayout(ctx);
                br.setOrientation(LinearLayout.HORIZONTAL);
                br.setGravity(Gravity.CENTER_VERTICAL);
                br.setPadding(0, (int)(4*d), 0, (int)(4*d));

                TextView fn = new TextView(ctx);
                fn.setText(f.getName());
                fn.setTextSize(11); fn.setTextColor(AppColors.text1());
                fn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                br.addView(fn);

                TextView imp = new TextView(ctx);
                imp.setText("恢复");
                imp.setTextSize(11); imp.setTextColor(AppColors.accent());
                imp.setPadding((int)(8*d), 0, (int)(4*d), 0);
                imp.setOnClickListener(v2 -> {
                    int cnt = LabelBackup.importFromJson(f.getAbsolutePath(), true);
                    Toast.makeText(parentAct, "已恢复 " + cnt + " 个标签", Toast.LENGTH_SHORT).show();
                });
                br.addView(imp);

                TextView del = new TextView(ctx);
                del.setText("删除");
                del.setTextSize(11); del.setTextColor(AppColors.error());
                del.setPadding((int)(4*d), 0, 0, 0);
                del.setOnClickListener(v2 -> {
                    LabelBackup.deleteBackup(f.getAbsolutePath());
                    buildBackupRestore(ctx, parentAct, d, content);
                });
                br.addView(del);
                backupList.addView(br);
            }
            sv.addView(backupList);
            card.addView(sv);
        }

        content.addView(card);
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
        showConfirmDialog(ctx, parentAct, d, "确认删除", "确定要删除分组 \"" + labelName + "\" 吗？", "删除", 0xFFE53935, () -> {
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
        dlgRoot.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.cardBg(ctx));
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
            ScrollView sv = new ScrollView(ctx);
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
        close.setPadding(0, (int)(12*d), 0, 0);
        close.setOnClickListener(v2 -> dialog.dismiss());
        dlgRoot.addView(close);

        dialog.setView(dlgRoot);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    private static void showAddRuleDialog(Context ctx, Activity parentAct, float d, LinearLayout content) {
        int dlgTheme = AppColors.isDarkMode() ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();
        LinearLayout dlgRoot = new LinearLayout(ctx);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView title = new TextView(ctx);
        title.setText("添加自动分组规则");
        title.setTextSize(16); title.setTextColor(AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, 0, (int)(10*d));
        dlgRoot.addView(title);

        EditText ruleNameEt = makeEditText(ctx, d, "规则名称");
        dlgRoot.addView(ruleNameEt);
        dlgRoot.addView(spacerV(ctx, d, 6));

        String[] types = {"备注包含", "昵称包含", "微信号匹配", "已有标签名匹配"};
        Spinner typeSp = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, types);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSp.setAdapter(adapter);
        dlgRoot.addView(typeSp);
        dlgRoot.addView(spacerV(ctx, d, 6));

        EditText matchValueEt = makeEditText(ctx, d, "匹配关键词/正则");
        dlgRoot.addView(matchValueEt);
        dlgRoot.addView(spacerV(ctx, d, 6));

        List<LabelInfo> labels = ChatGroupHook.getAllLabels();
        String[] labelNames = new String[Math.max(1, labels.size())];
        final int[] labelIds = new int[labelNames.length];
        if (labels.isEmpty()) { labelNames[0] = "请先创建分组"; labelIds[0] = -1; }
        else { for (int i = 0; i < labels.size(); i++) { labelNames[i] = labels.get(i).labelName; labelIds[i] = labels.get(i).labelId; } }

        Spinner labelSp = new Spinner(ctx);
        ArrayAdapter<String> ladapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, labelNames);
        ladapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        labelSp.setAdapter(ladapter);
        dlgRoot.addView(labelSp);
        dlgRoot.addView(spacerV(ctx, d, 10));

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        TextView cancel = new TextView(ctx); cancel.setText("取消"); cancel.setTextSize(14); cancel.setTextColor(AppColors.text2()); cancel.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d)); cancel.setOnClickListener(v2->dialog.dismiss()); btnRow.addView(cancel);
        TextView confirm = new TextView(ctx); confirm.setText("确认"); confirm.setTextSize(14); confirm.setTextColor(AppColors.accent()); confirm.setTypeface(null, Typeface.BOLD); confirm.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d));
        confirm.setOnClickListener(v2 -> {
            String rn = ruleNameEt.getText().toString().trim();
            String mv = matchValueEt.getText().toString().trim();
            if (rn.isEmpty() || mv.isEmpty()) { Toast.makeText(parentAct, "请填写完整", Toast.LENGTH_SHORT).show(); return; }
            int tid = labelIds[labelSp.getSelectedItemPosition()];
            if (tid < 0) { Toast.makeText(parentAct, "请先创建分组", Toast.LENGTH_SHORT).show(); return; }
            AutoRule.MatchType mt;
            switch (typeSp.getSelectedItemPosition()) {
                case 0: mt = AutoRule.MatchType.REMARK_CONTAINS; break;
                case 1: mt = AutoRule.MatchType.NICKNAME_CONTAINS; break;
                case 2: mt = AutoRule.MatchType.USERNAME_MATCHES; break;
                default: mt = AutoRule.MatchType.LABEL_NAME_MATCHES; break;
            }
            AutoRule rule = new AutoRule(AutoGroupEngine.nextRuleId(), rn, mt, mv, tid);
            AutoGroupEngine.addRule(rule);
            dialog.dismiss();
            buildAutoRules(ctx, parentAct, d, content);
            Toast.makeText(parentAct, "规则已添加", Toast.LENGTH_SHORT).show();
        });
        btnRow.addView(confirm);
        dlgRoot.addView(btnRow);
        dialog.setView(dlgRoot);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    private static void showMergeDialog(Context ctx, Activity parentAct, float d) {
        List<LabelInfo> labels = ChatGroupHook.getAllLabels();
        if (labels.size() < 2) { Toast.makeText(parentAct, "至少需要2个分组", Toast.LENGTH_SHORT).show(); return; }
        String[] labelNames = new String[labels.size()];
        int[] labelIds = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) { labelNames[i] = labels.get(i).labelName; labelIds[i] = labels.get(i).labelId; }

        int dlgTheme = AppColors.isDarkMode() ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();
        LinearLayout dlgRoot = new LinearLayout(ctx);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView title = new TextView(ctx); title.setText("合并标签"); title.setTextSize(16); title.setTextColor(AppColors.text1()); title.setTypeface(null, Typeface.BOLD); title.setPadding(0, 0, 0, (int)(4*d)); dlgRoot.addView(title);
        TextView desc = new TextView(ctx); desc.setText("将来源标签的联系人迁移到目标标签后删除来源"); desc.setTextSize(11); desc.setTextColor(AppColors.text2()); desc.setPadding(0, 0, 0, (int)(10*d)); dlgRoot.addView(desc);

        Spinner srcSp = new Spinner(ctx); srcSp.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, labelNames)); dlgRoot.addView(srcSp);
        dlgRoot.addView(spacerV(ctx, d, 4));
        Spinner dstSp = new Spinner(ctx); dstSp.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, labelNames)); dlgRoot.addView(dstSp);
        dlgRoot.addView(spacerV(ctx, d, 10));

        LinearLayout btnRow = new LinearLayout(ctx); btnRow.setOrientation(LinearLayout.HORIZONTAL); btnRow.setGravity(Gravity.CENTER);
        TextView cancel = new TextView(ctx); cancel.setText("取消"); cancel.setTextSize(14); cancel.setTextColor(AppColors.text2()); cancel.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d)); cancel.setOnClickListener(v2->dialog.dismiss()); btnRow.addView(cancel);
        TextView confirm = new TextView(ctx); confirm.setText("合并"); confirm.setTextSize(14); confirm.setTextColor(AppColors.accent()); confirm.setTypeface(null, Typeface.BOLD); confirm.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d));
        confirm.setOnClickListener(v2 -> {
            int src = labelIds[srcSp.getSelectedItemPosition()];
            int dst = labelIds[dstSp.getSelectedItemPosition()];
            if (src == dst) { Toast.makeText(parentAct, "不能合并到自身", Toast.LENGTH_SHORT).show(); return; }
            if (BatchOperator.mergeLabels(src, dst)) Toast.makeText(parentAct, "合并成功", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        btnRow.addView(confirm);
        dlgRoot.addView(btnRow);
        dialog.setView(dlgRoot);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    private static void showClearLabelDialog(Context ctx, Activity parentAct, float d) {
        List<LabelInfo> labels = ChatGroupHook.getAllLabels();
        if (labels.isEmpty()) { Toast.makeText(parentAct, "暂无分组", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[labels.size()];
        int[] ids = new int[labels.size()];
        for (int i = 0; i < labels.size(); i++) { names[i] = labels.get(i).labelName; ids[i] = labels.get(i).labelId; }

        int dlgTheme = AppColors.isDarkMode() ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();
        LinearLayout dlgRoot = new LinearLayout(ctx);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView title = new TextView(ctx); title.setText("清空标签联系人"); title.setTextSize(16); title.setTextColor(AppColors.text1()); title.setTypeface(null, Typeface.BOLD); title.setPadding(0,0,0,(int)(4*d)); dlgRoot.addView(title);

        Spinner sp = new Spinner(ctx); sp.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, names)); dlgRoot.addView(sp);
        dlgRoot.addView(spacerV(ctx, d, 10));

        LinearLayout btnRow = new LinearLayout(ctx); btnRow.setOrientation(LinearLayout.HORIZONTAL); btnRow.setGravity(Gravity.CENTER);
        TextView cancel = new TextView(ctx); cancel.setText("取消"); cancel.setTextSize(14); cancel.setTextColor(AppColors.text2()); cancel.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d)); cancel.setOnClickListener(v2->dialog.dismiss()); btnRow.addView(cancel);
        TextView confirm = new TextView(ctx); confirm.setText("清空"); confirm.setTextSize(14); confirm.setTextColor(0xFFE53935); confirm.setTypeface(null, Typeface.BOLD); confirm.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d));
        confirm.setOnClickListener(v2 -> { int n = BatchOperator.clearLabelContacts(ids[sp.getSelectedItemPosition()]); Toast.makeText(parentAct, "已清空 " + n + " 个联系人", Toast.LENGTH_SHORT).show(); dialog.dismiss(); });
        btnRow.addView(confirm);
        dlgRoot.addView(btnRow);
        dialog.setView(dlgRoot);
        InsetsUtil.transparentWindow(dialog);
        dialog.show();
    }

    private static void showStatsDialog(Context ctx, Activity parentAct, float d) {
        Map<String, Object> stats = BatchOperator.getStatistics();
        int dlgTheme = AppColors.isDarkMode() ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        AlertDialog dialog = new AlertDialog.Builder(ctx, dlgTheme).create();
        LinearLayout dlgRoot = new LinearLayout(ctx);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView title = new TextView(ctx); title.setText("统计信息"); title.setTextSize(16); title.setTextColor(AppColors.text1()); title.setTypeface(null, Typeface.BOLD); title.setPadding(0,0,0,(int)(8*d)); dlgRoot.addView(title);
        for (Map.Entry<String, Object> e : stats.entrySet()) {
            TextView tv = new TextView(ctx);
            tv.setText(e.getKey() + ": " + e.getValue());
            tv.setTextSize(12); tv.setTextColor(AppColors.text1());
            tv.setPadding(0, (int)(3*d), 0, (int)(3*d));
            dlgRoot.addView(tv);
        }
        dlgRoot.addView(spacerV(ctx, d, 8));
        TextView close = new TextView(ctx); close.setText("关闭"); close.setTextSize(14); close.setTextColor(AppColors.text2()); close.setGravity(Gravity.CENTER); close.setPadding(0,(int)(8*d),0,0); close.setOnClickListener(v2->dialog.dismiss()); dlgRoot.addView(close);
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
        dlgRoot.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView dlgTitle = new TextView(ctx); dlgTitle.setText(title); dlgTitle.setTextSize(16); dlgTitle.setTextColor(AppColors.text1()); dlgTitle.setTypeface(null, Typeface.BOLD); dlgTitle.setPadding(0,0,0,(int)(10*d)); dlgRoot.addView(dlgTitle);
        dlgRoot.addView(et);
        dlgRoot.addView(spacerV(ctx, d, 10));

        LinearLayout btnRow = new LinearLayout(ctx); btnRow.setOrientation(LinearLayout.HORIZONTAL); btnRow.setGravity(Gravity.CENTER);
        TextView cancel = new TextView(ctx); cancel.setText("取消"); cancel.setTextSize(14); cancel.setTextColor(AppColors.text2()); cancel.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d)); cancel.setOnClickListener(v2->dialog.dismiss()); btnRow.addView(cancel);
        TextView confirm = new TextView(ctx); confirm.setText("确认"); confirm.setTextSize(14); confirm.setTextColor(AppColors.accent()); confirm.setTypeface(null, Typeface.BOLD); confirm.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d));
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
        dlgRoot.setPadding((int)(14*d), (int)(14*d), (int)(14*d), (int)(8*d));
        dlgRoot.setBackground(CandyUi.cardBg(ctx));
        InsetsUtil.clipRounded(dlgRoot);

        TextView dlgTitle = new TextView(ctx); dlgTitle.setText(title); dlgTitle.setTextSize(16); dlgTitle.setTextColor(AppColors.text1()); dlgTitle.setTypeface(null, Typeface.BOLD); dlgTitle.setPadding(0,0,0,(int)(4*d)); dlgRoot.addView(dlgTitle);
        TextView msgTv = new TextView(ctx); msgTv.setText(msg); msgTv.setTextSize(13); msgTv.setTextColor(AppColors.text2()); msgTv.setPadding(0,0,0,(int)(10*d)); dlgRoot.addView(msgTv);

        LinearLayout btnRow = new LinearLayout(ctx); btnRow.setOrientation(LinearLayout.HORIZONTAL); btnRow.setGravity(Gravity.CENTER);
        TextView cancel = new TextView(ctx); cancel.setText("取消"); cancel.setTextSize(14); cancel.setTextColor(AppColors.text2()); cancel.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d)); cancel.setOnClickListener(v2->dialog.dismiss()); btnRow.addView(cancel);
        TextView confirm = new TextView(ctx); confirm.setText(btnText); confirm.setTextSize(14); confirm.setTextColor(btnColor); confirm.setTypeface(null, Typeface.BOLD); confirm.setPadding((int)(20*d),(int)(8*d),(int)(20*d),(int)(8*d));
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
        bg.setCornerRadius((int)(6*d));
        bg.setStroke((int)(1.5f*d), AppColors.candyPink());
        et.setBackground(bg);
        return et;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(8*d), (int)(14*d), (int)(8*d));
        row.setBackground(CandyUi.rowPressBg(ctx));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(13); tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(tv);

        Switch sw = CandyUi.newSwitch(ctx);
        sw.setChecked(checked);
        if (listener != null) sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(CandyUi.cardBg(ctx));
        int p = (int)(4*d);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, (int)(8*d));
        card.setLayoutParams(lp);
        return card;
    }

    private static View candyDivider(Context ctx, float d) {
        return M3Page.divider(ctx);
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp*d)));
        return v;
    }
}
