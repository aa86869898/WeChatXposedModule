package com.leshao.v3.ui;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import com.leshao.v3.ui.widgets.M3Page;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DataToolsPageView {

    private static final SimpleDateFormat fileSdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private static String backupDir(Context ctx) {
        return ctx.getFilesDir().getAbsolutePath() + "/leshao_v3_logs/backup/";
    }
    private static String exportDir(Context ctx) {
        return ctx.getFilesDir().getAbsolutePath() + "/leshao_v3_logs/exports/";
    }
    private static String chatExportDir(Context ctx) {
        return ctx.getFilesDir().getAbsolutePath() + "/leshao_v3_logs/chat_exports/";
    }

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
            showBackupProgressDialog(ctx, parentAct, d);
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
                    int deleted = deleteAllFiles(new File(exportDir(ctx)));
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

        // ===== 一键导入导出聊天记录(JSON) =====
        root.addView(spacer(ctx, d, 8));
        LinearLayout card3 = makeCard(ctx, d);
        card3.addView(switchRow(ctx, d, "一键导入/导出聊天记录(JSON)",
                "遍历全部会话分页导出为 JSON · 从 JSON 恢复消息(不设 msgId 由微信自增)",
                true, (v, on) -> {}));
        root.addView(card3);

        root.addView(spacer(ctx, d, 8));
        btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding((int)(2*d), 0, (int)(2*d), 0);
        btnRow.addView(actionButton(ctx, d, "导出全部聊天记录", 1f, () -> {
            showChatExportDialog(ctx, parentAct);
        }));
        View gap3 = new View(ctx);
        gap3.setLayoutParams(new LinearLayout.LayoutParams((int)(8*d), -2));
        btnRow.addView(gap3);
        btnRow.addView(actionButton(ctx, d, "导入聊天记录(JSON)", 1f, () -> {
            showChatImportDialog(ctx, parentAct);
        }));
        root.addView(btnRow);

        root.addView(spacer(ctx, d, 8));
        btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding((int)(2*d), 0, (int)(2*d), 0);
        btnRow.addView(actionButton(ctx, d, "清除聊天记录导出文件", 1f, () -> {
            new AlertDialog.Builder(ctx)
                .setTitle("确认清除")
                .setMessage("确定要清除所有聊天记录 JSON 导出文件吗?")
                .setPositiveButton("清除", (dialog, which) -> {
                    int deleted = deleteAllFiles(new File(chatExportDir(ctx)));
                    Toast.makeText(ctx, "已删除 " + deleted + " 个文件", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
        }));
        View gap4 = new View(ctx);
        gap4.setLayoutParams(new LinearLayout.LayoutParams((int)(8*d), -2));
        btnRow.addView(gap4);
        btnRow.addView(actionButton(ctx, d, "查看导出文件", 1f, () -> showChatExportList(ctx)));
        root.addView(btnRow);

        root.addView(spacer(ctx, d, 8));
        LinearLayout card5 = makeCard(ctx, d);
        card5.addView(sectionLabel(ctx, "微信更新管控"));
        card5.addView(switchRow(ctx, d, "禁止微信热更新",
                "阻断版本升级与 Tinker 热补丁，避免公众号/微信用静默更新", cfg.blockWechatUpdate, (v, on) -> {
            cfg.blockWechatUpdate = on; cfg.save(prefs); WeChatUpdateBlocker.setEnabled(on);
        }));
        card5.addView(actionButton(ctx, d, "立即应用管控", 1f, () -> {
            WeChatUpdateBlocker.setEnabled(cfg.blockWechatUpdate);
            Toast.makeText(ctx, "管控设置已保存，重启微信后生效", Toast.LENGTH_SHORT).show();
        }));
        root.addView(card5);

        sv.addView(root);
        return sv;
    }

    private static void showBackupProgressDialog(Context ctx, Activity parentAct, float d) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(20*d), (int)(16*d), (int)(20*d), (int)(16*d));
        root.setBackgroundColor(AppColors.bg());

        TextView titleTv = new TextView(ctx);
        titleTv.setText("正在备份聊天记录...");
        titleTv.setTextSize(15);
        titleTv.setTextColor(AppColors.text1());
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, 0, 0, (int)(12*d));
        root.addView(titleTv);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(ctx,
                null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(-1, (int)(10*d));
        root.addView(pb, pblp);

        TextView statusTv = new TextView(ctx);
        statusTv.setText("准备中... 0%");
        statusTv.setTextSize(12);
        statusTv.setTextColor(AppColors.text2());
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, (int)(10*d), 0, 0);
        root.addView(statusTv);

        AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setView(root)
                .setCancelable(false)
                .create();

        ChatBackup.ProgressListener listener = new ChatBackup.ProgressListener() {
            @Override
            public void onProgress(final int percent) {
                if (parentAct == null) return;
                parentAct.runOnUiThread(() -> {
                    pb.setProgress(percent);
                    statusTv.setText("正在备份... " + percent + "%");
                });
            }

            @Override
            public void onDone(final boolean ok, final String backupDir, final long totalSize) {
                if (parentAct == null) return;
                parentAct.runOnUiThread(() -> {
                    try { dlg.dismiss(); } catch (Throwable ignored) {}
                    if (ok && backupDir != null) {
                        String msg = "备份完成\n文件大小: " + formatSize(totalSize)
                                + "\n备份路径: " + backupDir;
                        new AlertDialog.Builder(ctx)
                                .setTitle("备份成功")
                                .setMessage(msg)
                                .setPositiveButton("好的", null)
                                .show();
                    } else {
                        Toast.makeText(ctx, "备份失败,请检查存储权限", Toast.LENGTH_LONG).show();
                    }
                });
            }
        };

        dlg.show();
        ChatBackup.triggerManualBackup(listener);
    }

    private static void showBackupList(Context ctx) {
        showFileListDialog(ctx, "备份文件列表", new File(backupDir(ctx)), "EnMicroMsg_");
    }

    /* ===== 一键导入/导出聊天记录(JSON) ===== */

    private static void showChatExportDialog(Context ctx, Activity parentAct) {
        new AlertDialog.Builder(ctx)
                .setTitle("导出全部聊天记录(JSON)")
                .setMessage("将遍历全部会话并分页导出为 JSON 文件。\n大号微信数据可能耗时较长，请耐心等待。")
                .setPositiveButton("开始导出", (dialog, which) -> {
                    ClassLoader cl = ContextManager.getClassLoader();
                    if (cl == null) {
                        Toast.makeText(ctx, "未捕获到微信 ClassLoader", Toast.LENGTH_LONG).show();
                        return;
                    }
                    File dir = new File(chatExportDir(ctx));
                    if (!dir.exists()) dir.mkdirs();
                    String path = new File(dir, "chat_export_" + System.currentTimeMillis() + ".json")
                            .getAbsolutePath();
                    Toast.makeText(ctx, "正在后台导出...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        try {
                            int n = com.leshao.v3.hook.ChatExportManager.exportAll(cl, path);
                            final int fn = n;
                            runOnUi(parentAct, () -> Toast.makeText(ctx,
                                    "导出成功：" + fn + " 条\n" + path, Toast.LENGTH_LONG).show());
                        } catch (Throwable t) {
                            runOnUi(parentAct, () -> Toast.makeText(ctx,
                                    "导出失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }, "leshao-chat-export").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showChatImportDialog(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(12*d));
        root.setBackgroundColor(AppColors.bg());

        TextView header = new TextView(ctx);
        header.setText("导入聊天记录(JSON)");
        header.setTextSize(15);
        header.setTextColor(AppColors.text1());
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, (int)(8*d));
        root.addView(header);

        TextView tip = new TextView(ctx);
        tip.setText("从「导出全部聊天记录」生成的 chat_export_*.json 中选择。\n不设置 msgId，由微信自增分配，保留 createTime 保证排序。");
        tip.setTextSize(12);
        tip.setTextColor(AppColors.text2());
        tip.setPadding(0, 0, 0, (int)(10*d));
        root.addView(tip);

        // 导出目录文件列表
        File dir = new File(chatExportDir(ctx));
        java.util.List<File> files = new ArrayList<>();
        if (dir.exists()) {
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isFile() && f.getName().endsWith(".json")) files.add(f);
                }
            }
        }
        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        if (!files.isEmpty()) {
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
                row.addView(infoTv);
                final File selected = f;
                row.setOnClickListener(v -> confirmChatImport(ctx, parentAct, selected));
                card.addView(row);
            }
            root.addView(card);
        } else {
            TextView empty = new TextView(ctx);
            empty.setText("未找到聊天记录 JSON 导出文件\n请先执行「导出全部聊天记录」");
            empty.setTextSize(14);
            empty.setTextColor(AppColors.text2());
            empty.setPadding((int)(14*d), (int)(12*d), 0, 0);
            root.addView(empty);
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);
        new AlertDialog.Builder(ctx)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static void confirmChatImport(Context ctx, Activity parentAct, File file) {
        new AlertDialog.Builder(ctx)
                .setTitle("确认导入")
                .setMessage("将把 " + file.getName() + " 中的消息插入当前微信数据库。\n" +
                        "已存在的消息(按会话+时间)可能重复，请谨慎操作。")
                .setPositiveButton("导入", (dialog, which) -> {
                    ClassLoader cl = ContextManager.getClassLoader();
                    if (cl == null) {
                        Toast.makeText(ctx, "未捕获到微信 ClassLoader", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(ctx, "正在后台导入...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        try {
                            int n = com.leshao.v3.hook.ChatImportManager.importFile(cl, file.getAbsolutePath());
                            final int fn = n;
                            runOnUi(parentAct, () -> Toast.makeText(ctx,
                                    "导入成功：" + fn + " 条\n(重启微信后可见)", Toast.LENGTH_LONG).show());
                        } catch (Throwable t) {
                            runOnUi(parentAct, () -> Toast.makeText(ctx,
                                    "导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    }, "leshao-chat-import").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showChatExportList(Context ctx) {
        showFileListDialog(ctx, "聊天记录导出文件", new File(chatExportDir(ctx)), "chat_export_");
    }

    private static void runOnUi(Activity parentAct, Runnable r) {
        if (parentAct != null) {
            parentAct.runOnUiThread(r);
        } else {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(r);
        }
    }

    private static void showExportList(Context ctx) {
        showFileListDialog(ctx, "导出记录列表", new File(exportDir(ctx)), null);
    }

    private static void showRestoreDialog(Context ctx) {
        showFilePickDialog(ctx, "选择备份文件恢复", new File(backupDir(ctx)), "EnMicroMsg_", (file) -> {
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
        File importRoot = new File(ctx.getFilesDir(), "leshao_v3_logs");
        findDbFilesRecursive(importRoot, dbFiles, 3);

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
        ib.setColor(AppColors.inputBg());
        ib.setCornerRadius((int)(6*d));
        ib.setStroke((int)(1.5f * d), AppColors.candyPink());
        pathInput.setBackground(ib);
        LinearLayout.LayoutParams pilp = new LinearLayout.LayoutParams(-1, -2);
        pilp.setMargins(0, 0, 0, (int)(10*d));
        pathInput.setLayoutParams(pilp);
        root.addView(pathInput);

        android.widget.TextView customBtn = new android.widget.TextView(ctx);
        customBtn.setText("从自定义路径导入");
        customBtn.setTextSize(12);
        customBtn.setTextColor(AppColors.accent());
        customBtn.setGravity(Gravity.CENTER);
        customBtn.setPadding((int)(14*d), (int)(8*d), (int)(14*d), (int)(8*d));
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setCornerRadius((int)(4*d));
        cbg.setStroke((int)(1*d), AppColors.accent());
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
        customBtn.setPaintFlags(customBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
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
            empty.setText("内部备份目录中未找到数据库文件");
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

        Switch sw = CandyUi.newSwitch(ctx); sw.setChecked(checked);
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
        tv.setPaintFlags(tv.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
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

    private static View candyDivider(Context ctx, float d) {
        return M3Page.divider(ctx);
    }
}
