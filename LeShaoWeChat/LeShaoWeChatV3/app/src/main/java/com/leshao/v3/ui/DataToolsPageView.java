package com.leshao.v3.ui;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.hook.*;
import com.leshao.v3.model.ModuleConfig;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DataToolsPageView {

    private static final SimpleDateFormat fileSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final String BACKUP_DIR = "/sdcard/leshao_v3_logs/backup/";
    private static final String EXPORT_DIR = "/sdcard/leshao_v3_logs/exports/";

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        SharedPreferences prefs = ContextManager.getPrefs();
        ModuleConfig cfg = ModuleConfig.load(prefs);

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        LinearLayout card1 = makeCard(ctx, d);
        card1.addView(switchRow(ctx, d, "消息导出", "在聊天窗口菜单导出会话记录为 TXT/HTML", cfg.msgExportEnabled, (v, on) -> {
            cfg.msgExportEnabled = on; cfg.save(prefs); MsgExport.setEnabled(on);
        }));
        root.addView(card1);

        root.addView(spacer(ctx, d, 8));

        LinearLayout card2 = makeCard(ctx, d);
        card2.addView(switchRow(ctx, d, "自动每日备份", "每天 02:00 自动备份加密数据库文件", cfg.chatBackupEnabled, (v, on) -> {
            cfg.chatBackupEnabled = on; cfg.save(prefs); ChatBackup.setEnabled(on);
        }));
        root.addView(card2);

        root.addView(spacer(ctx, d, 8));
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding((int)(2*d), 0, (int)(2*d), 0);
        btnRow.addView(actionButton(ctx, d, "立即备份", 1f, () -> {
            ChatBackup.triggerManualBackup();
            Toast.makeText(ctx, "正在备份,请稍候...", Toast.LENGTH_LONG).show();
        }));
        View gap = new View(ctx);
        gap.setLayoutParams(new LinearLayout.LayoutParams((int)(8*d), -2));
        btnRow.addView(gap);
        btnRow.addView(actionButton(ctx, d, "查看备份", 1f, () -> showBackupList(ctx)));
        root.addView(btnRow);

        root.addView(spacer(ctx, d, 8));
        btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding((int)(2*d), 0, (int)(2*d), 0);
        btnRow.addView(actionButton(ctx, d, "清除导出记录", 1f, () -> {
            new AlertDialog.Builder(ctx)
                .setTitle("确认清除")
                .setMessage("确定要清除所有导出记录文件吗?")
                .setPositiveButton("清除", (dialog, which) -> {
                    int deleted = deleteAllFiles(new File(EXPORT_DIR));
                    Toast.makeText(ctx, "已删除 " + deleted + " 个文件", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
        }));
        View gap2 = new View(ctx);
        gap2.setLayoutParams(new LinearLayout.LayoutParams((int)(8*d), -2));
        btnRow.addView(gap2);
        btnRow.addView(actionButton(ctx, d, "清除变更记录", 1f, () -> {
            new AlertDialog.Builder(ctx)
                .setTitle("确认清除")
                .setMessage("确定要清除所有通讯录变更记录吗?")
                .setPositiveButton("清除", (dialog, which) -> ContactChangeLog.clearRecords())
                .setNegativeButton("取消", null)
                .show();
        }));
        root.addView(btnRow);

        sv.addView(root);
        return sv;
    }

    private static void showBackupList(Context ctx) {
        showFileListDialog(ctx, "备份文件列表", new File(BACKUP_DIR), "EnMicroMsg_");
    }

    private static void showExportList(Context ctx) {
        showFileListDialog(ctx, "导出记录列表", new File(EXPORT_DIR), null);
    }

    private static void showRestoreDialog(Context ctx) {
        showFilePickDialog(ctx, "选择备份文件恢复", new File(BACKUP_DIR), "EnMicroMsg_", (file) -> {
            new AlertDialog.Builder(ctx)
                .setTitle("确认恢复")
                .setMessage("将用 " + file.getName() + " 恢复聊天记录数据库?\n恢复后需重新打开微信生效。")
                .setPositiveButton("恢复", (dialog, which) -> {
                    ChatBackup.triggerRestore(file.getAbsolutePath());
                    Toast.makeText(ctx, "正在恢复,请稍候...", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
        });
    }

    private static void showImportDialog(Context ctx) {
        java.util.List<File> dbFiles = new java.util.ArrayList<>();
        findDbFilesRecursive(new java.io.File("/sdcard/"), dbFiles, 3);

        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));
        root.setBackgroundColor(AppColors.bg());

        TextView header = new TextView(ctx);
        header.setText("导入外部数据库 (" + dbFiles.size() + "个)");
        header.setTextSize(15);
        header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, (int)(8*d));
        root.addView(header);

        // 自定义路径输入
        android.widget.EditText pathInput = new android.widget.EditText(ctx);
        pathInput.setHint("或输入自定义路径 (如 /sdcard/Download/backup.db)");
        pathInput.setTextSize(12);
        pathInput.setTextColor(AppColors.text1());
        pathInput.setHintTextColor(AppColors.text2());
        pathInput.setSingleLine(true);
        pathInput.setPadding((int)(12*d), (int)(10*d), (int)(12*d), (int)(10*d));
        pathInput.setBackgroundColor(AppColors.inputBg());
        android.graphics.drawable.GradientDrawable ib = new android.graphics.drawable.GradientDrawable();
        ib.setCornerRadius((int)(4*d));
        ib.setStroke(1, AppColors.border());
        pathInput.setBackground(ib);
        LinearLayout.LayoutParams pilp = new LinearLayout.LayoutParams(-1, -2);
        pilp.setMargins(0, 0, 0, (int)(10*d));
        pathInput.setLayoutParams(pilp);
        root.addView(pathInput);

        android.widget.TextView customBtn = new android.widget.TextView(ctx);
        customBtn.setText("从自定义路径导入");
        customBtn.setTextSize(12);
        customBtn.setTextColor(0xFF4A90D9);
        customBtn.setGravity(Gravity.CENTER);
        customBtn.setPadding((int)(14*d), (int)(8*d), (int)(14*d), (int)(8*d));
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setCornerRadius((int)(4*d));
        cbg.setStroke((int)(1*d), 0xFF4A90D9);
        cbg.setColor(android.graphics.Color.TRANSPARENT);
        customBtn.setBackground(cbg);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(-1, -2);
        cblp.setMargins(0, 0, 0, (int)(12*d));
        customBtn.setLayoutParams(cblp);
        customBtn.setOnClickListener(v -> {
            String p = pathInput.getText().toString().trim();
            if (p.isEmpty()) {
                Toast.makeText(ctx, "请输入文件路径", Toast.LENGTH_SHORT).show();
                return;
            }
            java.io.File cf = new java.io.File(p);
            if (!cf.exists()) {
                Toast.makeText(ctx, "文件不存在: " + p, Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(ctx)
                .setTitle("确认导入")
                .setMessage("将导入 " + cf.getName() + " 到微信数据库?\n操作可能覆盖当前聊天记录。")
                .setPositiveButton("导入", (dialog, which) -> {
                    ChatBackup.triggerRestore(cf.getAbsolutePath());
                    Toast.makeText(ctx, "已触发导入", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
        });
        root.addView(customBtn);

        TextView sep = new TextView(ctx);
        sep.setText("-- 或从下方扫描的文件中选择 --");
        sep.setTextSize(11);
        sep.setTextColor(AppColors.text2());
        sep.setGravity(Gravity.CENTER);
        sep.setPadding(0, 0, 0, (int)(8*d));
        root.addView(sep);

        LinearLayout card = makeCard(ctx, d);
        int count = 0;
        for (File f : dbFiles) {
            if (count >= 30) break;
            if (count > 0) {
                View div = new View(ctx);
                div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
                div.setBackgroundColor(AppColors.divider());
                card.addView(div);
            }

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
            row.setBackgroundColor(AppColors.whiteCard());

            TextView nameTv = new TextView(ctx);
            nameTv.setText(f.getName());
            nameTv.setTextSize(13);
            nameTv.setTextColor(AppColors.text1());
            row.addView(nameTv);

            TextView pathTv = new TextView(ctx);
            pathTv.setText(f.getParent());
            pathTv.setTextSize(10);
            pathTv.setTextColor(AppColors.text2());
            pathTv.setPadding(0, (int)(2*d), 0, 0);
            row.addView(pathTv);

            final File selectedFile = f;
            row.setOnClickListener(v -> {
                new AlertDialog.Builder(ctx)
                    .setTitle("确认导入")
                    .setMessage("将导入 " + selectedFile.getName() + " 到微信数据库?\n操作可能覆盖当前聊天记录。")
                    .setPositiveButton("导入", (dialog, which) -> {
                        ChatBackup.triggerRestore(selectedFile.getAbsolutePath());
                        Toast.makeText(ctx, "已触发导入,稍后微信打开时将自动执行", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            });
            card.addView(row);
            count++;
        }

        if (dbFiles.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("未在 /sdcard/ 中找到数据库文件");
            empty.setTextSize(14);
            empty.setTextColor(AppColors.text2());
            empty.setPadding((int)(14*d), (int)(12*d), 0, 0);
            root.addView(empty);
        } else {
            root.addView(card);
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);

        new AlertDialog.Builder(ctx)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show();
    }

    private static void showFilePickDialog(Context ctx, String title, File dir, String prefix, java.util.function.Consumer<File> onPick) {
        java.util.List<File> files = new java.util.ArrayList<>();
        if (dir.exists()) {
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isFile() && (prefix == null || f.getName().startsWith(prefix))) {
                        files.add(f);
                    }
                }
            }
        }
        java.util.Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));
        root.setBackgroundColor(AppColors.bg());

        TextView header = new TextView(ctx);
        header.setText(title + " (" + files.size() + "个)");
        header.setTextSize(15);
        header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, (int)(12*d));
        root.addView(header);

        if (files.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无可用文件");
            empty.setTextSize(14);
            empty.setTextColor(AppColors.text2());
            empty.setPadding(0, (int)(8*d), 0, 0);
            root.addView(empty);
        } else {
            LinearLayout card = makeCard(ctx, d);
            for (int i = 0; i < files.size(); i++) {
                File f = files.get(i);
                if (i > 0) {
                    View div = new View(ctx);
                    div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
                    div.setBackgroundColor(AppColors.divider());
                    card.addView(div);
                }

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
                row.setBackgroundColor(AppColors.whiteCard());

                TextView nameTv = new TextView(ctx);
                nameTv.setText(f.getName());
                nameTv.setTextSize(13);
                nameTv.setTextColor(AppColors.text1());
                row.addView(nameTv);

                TextView infoTv = new TextView(ctx);
                infoTv.setText(fileSdf.format(new Date(f.lastModified())) + "  " + formatSize(f.length()));
                infoTv.setTextSize(11);
                infoTv.setTextColor(AppColors.text2());
                infoTv.setPadding(0, (int)(3*d), 0, 0);
                row.addView(infoTv);

                final File selectedFile = f;
                row.setOnClickListener(v -> onPick.accept(selectedFile));
                card.addView(row);
            }
            root.addView(card);
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);

        new AlertDialog.Builder(ctx)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show();
    }

    private static void findDbFilesRecursive(File dir, java.util.List<File> out, int depth) {
        if (depth < 0 || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    findDbFilesRecursive(f, out, depth - 1);
                } else if (f.isFile()) {
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm")) {
                        out.add(f);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void showFileListDialog(Context ctx, String title, File dir, String filterPrefix) {
        List<File> files = new ArrayList<>();
        if (dir.exists()) {
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isFile()) {
                        if (filterPrefix == null || f.getName().startsWith(filterPrefix)) {
                            files.add(f);
                        }
                    }
                }
            }
        }

        float d = ctx.getResources().getDisplayMetrics().density;
        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));
        root.setBackgroundColor(AppColors.bg());

        TextView header = new TextView(ctx);
        header.setText(title + " (" + files.size() + "个)");
        header.setTextSize(15);
        header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, (int)(12*d));
        root.addView(header);

        if (files.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无记录");
            empty.setTextSize(14);
            empty.setTextColor(AppColors.text2());
            empty.setPadding(0, (int)(8*d), 0, 0);
            root.addView(empty);
        } else {
            LinearLayout card = makeCard(ctx, d);
            for (int i = 0; i < Math.min(files.size(), 50); i++) {
                File f = files.get(i);
                String name = f.getName();
                String date = fileSdf.format(new Date(f.lastModified()));
                String size = formatSize(f.length());

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding((int)(14*d), (int)(10*d), (int)(14*d), (int)(10*d));
                row.setBackgroundColor(AppColors.whiteCard());

                LinearLayout topRow = new LinearLayout(ctx);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView nameTv = new TextView(ctx);
                nameTv.setText(name);
                nameTv.setTextSize(13);
                nameTv.setTextColor(AppColors.text1());
                nameTv.setMaxLines(1);
                nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                topRow.addView(nameTv);

                TextView sizeTv = new TextView(ctx);
                sizeTv.setText(size);
                sizeTv.setTextSize(11);
                sizeTv.setTextColor(AppColors.text2());
                sizeTv.setPadding((int)(8*d), 0, 0, 0);
                topRow.addView(sizeTv);

                row.addView(topRow);

                TextView dateTv = new TextView(ctx);
                dateTv.setText(date);
                dateTv.setTextSize(11);
                dateTv.setTextColor(AppColors.text2());
                dateTv.setPadding(0, (int)(3*d), 0, 0);
                row.addView(dateTv);

                if (i > 0) {
                    View div = new View(ctx);
                    div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
                    div.setBackgroundColor(AppColors.divider());
                    card.addView(div);
                }
                card.addView(row);
            }
            root.addView(card);
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);

        new AlertDialog.Builder(ctx)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show();
    }

    private static int deleteAllFiles(File dir) {
        int count = 0;
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.delete()) count++;
                }
            }
        }
        return count;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
        card.setBackgroundColor(AppColors.card());
        return card;
    }

    private static LinearLayout switchRow(Context ctx, float d, String title, String desc,
                                           boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14 * d), (int)(12 * d), (int)(14 * d), (int)(12 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView tv = new TextView(ctx);
        tv.setText(title); tv.setTextSize(15);
        tv.setTextColor(AppColors.text1()); tv.setTypeface(null, Typeface.BOLD);
        textCol.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(desc); dv.setTextSize(12);
            dv.setTextColor(AppColors.text2());
            dv.setPadding(0, (int)(3 * d), 0, 0);
            textCol.addView(dv);
        }
        row.addView(textCol);

        Switch sw = new Switch(ctx); sw.setChecked(checked);
        try { sw.setThumbResource(android.R.drawable.btn_star_big_on); } catch (Throwable ignored) {}
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);
        return row;
    }

    private static TextView sectionLabel(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text); tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(8 * d));
        return tv;
    }

    private static View actionButton(Context ctx, float d, String label, float weight, Runnable action) {
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));
        btn.setBackgroundColor(AppColors.accent());
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, weight));
        btn.setClickable(true);
        btn.setOnClickListener(v -> {
            try { action.run(); } catch (Throwable ignored) {}
        });

        TextView tv = new TextView(ctx);
        tv.setText(label); tv.setTextSize(14);
        tv.setTextColor(AppColors.whiteTextOnAccent());
        tv.setGravity(Gravity.CENTER);
        btn.addView(tv);

        return btn;
    }

    private static View spacer(Context ctx, float d, int dpVal) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }

    private static View buttonRow(Context ctx, Activity parentAct, float d, String label, Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(14*d), (int)(12*d), (int)(14*d), (int)(12*d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView tv = new TextView(ctx);
        tv.setText(label); tv.setTextSize(15);
        tv.setTextColor(AppColors.accent());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView arrow = new TextView(ctx);
        arrow.setText(">"); arrow.setTextSize(16);
        arrow.setTextColor(AppColors.arrow());

        row.addView(tv);
        row.addView(arrow);
        row.setOnClickListener(v -> {
            try { action.run(); } catch (Throwable ignored) {}
        });
        return row;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
