package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.service.ActivationManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ProfilePageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setPadding((int)(8 * d), (int)(16 * d), (int)(8 * d), (int)(16 * d));

        // ===== 用户信息卡片 =====
        root.addView(sectionLabel(ctx, d, "用户信息"));
        LinearLayout infoCard = makeCard(ctx, d);

        // 头像+昵称/ID 水平布局
        LinearLayout userRow = new LinearLayout(ctx);
        userRow.setOrientation(LinearLayout.HORIZONTAL);
        userRow.setGravity(Gravity.CENTER_VERTICAL);
        userRow.setPadding((int)(16 * d), (int)(14 * d), (int)(16 * d), (int)(14 * d));

        // 头像左侧
        ImageView avatar = new ImageView(ctx);
        int avatarSize = (int)(56 * d);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        alp.setMargins(0, 0, (int)(14 * d), 0);
        avatar.setLayoutParams(alp);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setCornerRadius(avatarSize / 2f);
        avatarBg.setColor(AppColors.bg());
        avatar.setBackground(avatarBg);
        String avatarPath = MainActivity.getAvatarPath();
        if (avatarPath != null) {
            File f = new File(avatarPath);
            if (f.exists()) {
                Bitmap bm = BitmapFactory.decodeFile(avatarPath);
                if (bm != null) avatar.setImageBitmap(bm);
            }
        }
        userRow.addView(avatar);

        // 昵称+wxid 右侧
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView nickTv = new TextView(ctx);
        nickTv.setText(MainActivity.getUserNickname());
        nickTv.setTextSize(16);
        nickTv.setTextColor(AppColors.text1());
        nickTv.setTypeface(null, Typeface.BOLD);
        nickTv.setPadding(0, 0, 0, (int)(6 * d));
        textCol.addView(nickTv);

        LinearLayout wxidRow = new LinearLayout(ctx);
        wxidRow.setOrientation(LinearLayout.HORIZONTAL);
        wxidRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView wxidTv = new TextView(ctx);
        wxidTv.setText(MainActivity.getUserWxid());
        wxidTv.setTextSize(12);
        wxidTv.setTextColor(AppColors.text2());
        wxidRow.addView(wxidTv);

        TextView copyBtn = new TextView(ctx);
        copyBtn.setText("复制");
        copyBtn.setTextSize(10);
        copyBtn.setTextColor(AppColors.accent());
        copyBtn.setPadding((int)(8 * d), (int)(3 * d), (int)(8 * d), (int)(3 * d));
        GradientDrawable cpBg = new GradientDrawable();
        cpBg.setCornerRadius((int)(3 * d));
        cpBg.setStroke((int)(1 * d), AppColors.accent());
        cpBg.setColor(android.graphics.Color.TRANSPARENT);
        copyBtn.setBackground(cpBg);
        copyBtn.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData cd = android.content.ClipData.newPlainText("wxid", MainActivity.getUserWxid());
            cm.setPrimaryClip(cd);
            Toast.makeText(ctx, "微信ID已复制", Toast.LENGTH_SHORT).show();
        });
        wxidRow.addView(copyBtn);

        textCol.addView(wxidRow);
        userRow.addView(textCol);
        infoCard.addView(userRow);

        infoCard.addView(itemDivider(ctx, d));

        // 微信号
        String alias = MainActivity.getUserAlias();
        if (alias != null && !alias.isEmpty() && !alias.equals(MainActivity.getUserWxid())) {
            infoCard.addView(profileRow(ctx, d, "微信号", alias));
            infoCard.addView(itemDivider(ctx, d));
        }

        // 会员到期
        String expireText = getExpireTime();
        infoCard.addView(profileRow(ctx, d, "会员到期", expireText));

        root.addView(infoCard);

        // ===== 激活码 =====
        root.addView(spacerV(ctx, d, 16));
        root.addView(sectionLabel(ctx, d, "激活码"));
        LinearLayout actCard = makeCard(ctx, d);

        // 会员状态
        boolean isActive = ActivationManager.isActivated();
        String currentLevel = ActivationManager.getLevelName();
        String boundWxid = ActivationManager.getBoundWxid();
        String currentWxid = MainActivity.getUserWxid();

        TextView statusLabel = new TextView(ctx);
        statusLabel.setText("会员状态: " + currentLevel + (isActive && boundWxid.equals(currentWxid) ? " (已激活)" : ""));
        statusLabel.setTextSize(13);
        statusLabel.setTextColor(isActive ? AppColors.accent() : AppColors.arrow());
        statusLabel.setTypeface(null, Typeface.BOLD);
        statusLabel.setPadding((int)(16 * d), (int)(12 * d), (int)(16 * d), (int)(4 * d));
        actCard.addView(statusLabel);

        if (isActive) {
            TextView wxidLabel = new TextView(ctx);
            wxidLabel.setText("绑定ID: " + boundWxid);
            wxidLabel.setTextSize(11);
            wxidLabel.setTextColor(AppColors.text2());
            wxidLabel.setPadding((int)(16 * d), (int)(2 * d), (int)(16 * d), (int)(8 * d));
            actCard.addView(wxidLabel);

            // 管理员配置入口 (仅管理员可见)
            if (currentWxid != null && ActivationManager.isAdmin(currentWxid)) {
                actCard.addView(itemDivider(ctx, d));
                LinearLayout adminRow = new LinearLayout(ctx);
                adminRow.setOrientation(LinearLayout.HORIZONTAL);
                adminRow.setGravity(Gravity.CENTER_VERTICAL);
                adminRow.setPadding((int)(16 * d), (int)(8 * d), (int)(16 * d), (int)(8 * d));
                adminRow.setBackgroundColor(AppColors.whiteCard());

                TextView adminLabel = new TextView(ctx);
                adminLabel.setText("管理员配置");
                adminLabel.setTextSize(13);
                adminLabel.setTextColor(AppColors.accent());
                adminLabel.setTypeface(null, Typeface.BOLD);
                adminLabel.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
                adminRow.addView(adminLabel);

                TextView adminArrow = new TextView(ctx);
                adminArrow.setText(">");
                adminArrow.setTextSize(16);
                adminArrow.setTextColor(AppColors.text2());
                adminRow.addView(adminArrow);

                adminRow.setOnClickListener(v -> {
                    SubPageActivity.open(parentAct, "管理员工具", 98);
                });
                actCard.addView(adminRow);
            }
        }

        // 输入框 (未激活或wxid不匹配时显示)
        final boolean showInput = !isActive || !boundWxid.equals(currentWxid);
        if (showInput) {
            LinearLayout inputRow = new LinearLayout(ctx);
            inputRow.setOrientation(LinearLayout.HORIZONTAL);
            inputRow.setGravity(Gravity.CENTER_VERTICAL);
            inputRow.setPadding((int)(16 * d), (int)(8 * d), (int)(16 * d), (int)(12 * d));

            EditText codeInput = new EditText(ctx);
            codeInput.setHint("请输入激活码 (LS-开头)");
            codeInput.setTextSize(13);
            codeInput.setTextColor(AppColors.text1());
            codeInput.setHintTextColor(AppColors.text2());
            codeInput.setSingleLine(true);
            codeInput.setPadding((int)(12 * d), (int)(8 * d), (int)(12 * d), (int)(8 * d));
            codeInput.setBackgroundColor(AppColors.inputBg());

            GradientDrawable inputBorder = new GradientDrawable();
            inputBorder.setCornerRadius((int)(6 * d));
            inputBorder.setStroke(1, AppColors.border());
            codeInput.setBackground(inputBorder);

            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
            inputLp.setMargins(0, 0, (int)(8 * d), 0);
            codeInput.setLayoutParams(inputLp);
            inputRow.addView(codeInput);

            TextView btnActivate = makeBtn(ctx, d, "验证激活");
            inputRow.addView(btnActivate);

            btnActivate.setOnClickListener(v -> {
                String code = codeInput.getText().toString().trim();
                if (code.isEmpty()) {
                    Toast.makeText(ctx, "请输入激活码", Toast.LENGTH_SHORT).show();
                    return;
                }
                String wxid = MainActivity.getUserWxid();
                if (wxid == null || wxid.isEmpty()) {
                    Toast.makeText(ctx, "无法获取微信ID", Toast.LENGTH_SHORT).show();
                    return;
                }
                ActivationManager.ValidationResult result = ActivationManager.validate(code, wxid);
                if (result.valid) {
                    // 记录激活时间
                ContextManager.getPrefs().edit()
                    .putString("ls_act_time", String.valueOf(System.currentTimeMillis()))
                    .commit();
                    ActivationManager.saveActivation(ctx, code, wxid,
                        result.levelIndex, result.expireHours, result.featureMask);
                    statusLabel.setText("当前状态: " + result.levelName + " (已激活)");
                    statusLabel.setTextColor(AppColors.accent());
                    Toast.makeText(ctx, "激活成功: " + result.levelName, Toast.LENGTH_SHORT).show();
                    // 刷新页面
                    SubPageActivity.open(parentAct, "个人中心", 99);
                } else {
                    Toast.makeText(ctx, "激活码无效或不匹配当前微信", Toast.LENGTH_SHORT).show();
                }
            });

            actCard.addView(inputRow);
        }

        root.addView(actCard);

        final SharedPreferences prefs = ContextManager.getPrefs();

        // ===== Monet 主题 =====
        root.addView(spacerV(ctx, d, 16));

        // ===== 日志导出 =====
        root.addView(spacerV(ctx, d, 16));
        root.addView(sectionLabel(ctx, d, "日志导出"));
        LinearLayout logCard = makeCard(ctx, d);

        TextView logDesc = new TextView(ctx);
        logDesc.setText("将乐少助手运行日志导出到 /sdcard/leshao_v3_logs/");
        logDesc.setTextSize(12);
        logDesc.setTextColor(AppColors.text2());
        logDesc.setPadding((int)(16 * d), (int)(12 * d), (int)(16 * d), (int)(4 * d));
        logCard.addView(logDesc);

        LinearLayout logBtnRow = new LinearLayout(ctx);
        logBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        logBtnRow.setGravity(Gravity.CENTER);
        logBtnRow.setPadding((int)(16 * d), (int)(4 * d), (int)(16 * d), (int)(12 * d));

        TextView btnExport = makeSmallBtn(ctx, d, "导出日志", AppColors.accent());
        btnExport.setOnClickListener(v -> {
            try {
                String ts = String.valueOf(System.currentTimeMillis());
                java.io.File destDir = new java.io.File("/sdcard/leshao_v3_logs");
                if (!destDir.exists()) destDir.mkdirs();

                android.content.Context appCtx = com.leshao.v3.ContextManager.getAppContext();
                java.io.File leshaoRoot = appCtx != null
                    ? com.leshao.v3.PathUtil.getLeshaoRootDir(appCtx)
                    : new java.io.File("/data/data/com.tencent.mm/files/leshao_v3");

                int count = 0;
                java.io.File[] logDirs = {
                    leshaoRoot,
                    destDir
                };
                for (java.io.File dir : logDirs) {
                    java.io.File[] files = dir.listFiles();
                    if (files == null) continue;
                    for (java.io.File src : files) {
                        if (!src.isFile() || src.length() == 0) continue;
                        String destName = src.getName().replace(".", "_" + ts + ".");
                        java.io.File dest = new java.io.File(destDir, destName);
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
                             java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                        count++;
                    }
                }
                Toast.makeText(ctx, "已导出 " + count + " 个日志到 /sdcard/leshao_v3_logs/", Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Toast.makeText(ctx, "导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        logBtnRow.addView(btnExport);

        View btnSpacer = new View(ctx);
        btnSpacer.setLayoutParams(new LinearLayout.LayoutParams((int)(12*d), 0));
        logBtnRow.addView(btnSpacer);

        TextView btnOpenDir = makeSmallBtn(ctx, d, "打开日志目录", AppColors.text1());
        btnOpenDir.setOnClickListener(v -> {
            try {
                java.io.File logDir = new java.io.File("/sdcard/leshao_v3_logs");
                if (!logDir.exists()) logDir.mkdirs();
                android.net.Uri uri = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Aleshao_v3_logs");
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "resource/folder");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(intent);
            } catch (Throwable t) {
                Toast.makeText(ctx, "日志目录: /sdcard/leshao_v3_logs", Toast.LENGTH_LONG).show();
            }
        });
        logBtnRow.addView(btnOpenDir);

        logCard.addView(logBtnRow);
        root.addView(logCard);

        return root;
    }

    // ===== 组件工厂 =====

    private static View profileRow(Context ctx, float d, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(16 * d), (int)(10 * d), (int)(16 * d), (int)(10 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView labelTv = new TextView(ctx);
        labelTv.setText(label);
        labelTv.setTextSize(13);
        labelTv.setTextColor(AppColors.text2());
        labelTv.setLayoutParams(new LinearLayout.LayoutParams((int)(72 * d), -2));
        row.addView(labelTv);

        TextView valueTv = new TextView(ctx);
        valueTv.setText(value != null ? value : "");
        valueTv.setTextSize(13);
        valueTv.setTextColor(AppColors.text1());
        valueTv.setTypeface(null, Typeface.BOLD);
        valueTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(valueTv);

        return row;
    }

    private static TextView makeBtn(Context ctx, float d, String text) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setTextColor(AppColors.WHITE_TEXT);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setPadding((int)(16 * d), (int)(8 * d), (int)(16 * d), (int)(8 * d));
        btn.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius((int)(6 * d));
        bg.setColor(AppColors.accent());
        btn.setBackground(bg);

        return btn;
    }

    private static TextView makeSmallBtn(Context ctx, float d, String text, int color) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(AppColors.WHITE_TEXT);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setPadding((int)(14 * d), (int)(6 * d), (int)(14 * d), (int)(6 * d));
        btn.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius((int)(6 * d));
        bg.setColor(color);
        btn.setBackground(bg);

        return btn;
    }

    private static TextView sectionLabel(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text2());
        tv.setPadding(0, 0, 0, (int)(6 * d));
        return tv;
    }

    private static LinearLayout makeCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(2 * d), (int)(2 * d), (int)(2 * d), (int)(2 * d));
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
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dpVal * d)));
        return v;
    }

    private static String getExpireTime() {
        try {
            SharedPreferences prefs = ContextManager.getPrefs();
            if (prefs == null) return "未激活";
            String actTimeStr = prefs.getString("ls_act_time", "");
            String expireStr = prefs.getString("ls_act_expire", "0");
            if (actTimeStr.isEmpty()) return "未激活";
            long actTime = Long.parseLong(actTimeStr);
            int expireHours = Integer.parseInt(expireStr);
            if (ActivationManager.isPermanentMember()) return "2099年12月31日 23:59:59";
            if (expireHours <= 0) return "永久有效";
            long expireTime = actTime + expireHours * 3600000L;
            if (System.currentTimeMillis() > expireTime) return "已过期";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(expireTime));
        } catch (Throwable t) {
            return "未激活";
        }
    }
}
