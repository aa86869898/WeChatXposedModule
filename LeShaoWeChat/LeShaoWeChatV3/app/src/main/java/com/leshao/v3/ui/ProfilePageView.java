package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.service.ActivationManager;

import java.io.File;
import java.lang.reflect.Method;

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
        avatar.setClipToOutline(true);

        // 优先使用 AvatarHelper 多路径加载，回退到 getAvatarPath
        int avatarSizePx = (int)(56 * d);
        android.graphics.Bitmap bm = AvatarHelper.loadAvatar(MainActivity.getUserWxid(), avatarSizePx);
        if (bm != null) {
            avatar.setImageBitmap(bm);
        } else {
            String avatarPath = MainActivity.getAvatarPath();
            if (avatarPath != null) {
                java.io.File f = new java.io.File(avatarPath);
                if (f.exists()) {
                    final ImageView avatarIv = avatar;
                    final String fPath = avatarPath;
                    new Thread(() -> {
                        android.graphics.Bitmap bm2 = android.graphics.BitmapFactory.decodeFile(fPath);
                        if (bm2 != null) avatarIv.post(() -> avatarIv.setImageBitmap(bm2));
                    }, "leshao-avatar").start();
                }
            }
        }
        userRow.addView(avatar);

        // 昵称+wxid 右侧
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView nickTv = new TextView(ctx);
        nickTv.setText("加载中...");
        nickTv.setTextSize(16);
        nickTv.setTextColor(AppColors.text1());
        nickTv.setTypeface(null, Typeface.BOLD);
        nickTv.setPadding(0, 0, 0, (int)(6 * d));
        textCol.addView(nickTv);

        // 始终从 DB 加载真实昵称
        final String wxid = MainActivity.getUserWxid();
        final Context appCtx = ContextManager.getAppContext();
        new Thread(() -> {
            String realNick = null;
            if (appCtx != null) {
                try {
                    realNick = findNicknameFromDb(wxid, appCtx);
                } catch (Throwable ignored) {}
            }
            if (realNick == null || realNick.isEmpty()) {
                // 回退到 prefs/MainActivity 缓存
                realNick = MainActivity.getUserNickname();
            }
            if (realNick == null || realNick.isEmpty() || realNick.startsWith("wxid_")) {
                realNick = wxid;
            }
            final String finalNick = realNick;
            nickTv.post(() -> nickTv.setText(finalNick));
        }, "leshao-nickname").start();

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
        copyBtn.setPaintFlags(copyBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
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

        root.addView(infoCard);

        // 管理员配置入口 (仅管理员可见)
        String currentWxid = MainActivity.getUserWxid();
        if (currentWxid != null && ActivationManager.isAdmin(currentWxid)) {
            root.addView(candyDivider(ctx, d));
            root.addView(sectionLabel(ctx, d, "管理员工具"));
            LinearLayout adminCard = makeCard(ctx, d);

            LinearLayout adminRow = new LinearLayout(ctx);
            adminRow.setOrientation(LinearLayout.HORIZONTAL);
            adminRow.setGravity(Gravity.CENTER_VERTICAL);
            adminRow.setPadding((int)(16 * d), (int)(8 * d), (int)(16 * d), (int)(8 * d));
            adminRow.setBackgroundColor(AppColors.whiteCard());

            TextView adminLabel = new TextView(ctx);
            adminLabel.setText("模块黑名单管理");
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
            adminCard.addView(adminRow);
            root.addView(adminCard);
        }

        final SharedPreferences prefs = ContextManager.getPrefs();

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
        return "永久有效";
    }

    private static String findNicknameFromDb(String wxid, Context ctx) {
        if (wxid == null || ctx == null) return null;
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv == null) return null;
            long uin = Long.parseLong(uv.toString());

            String dbDir = ctx.getFilesDir().getParent();
            java.io.File[] dirs = new java.io.File(dbDir).listFiles();
            if (dirs == null) return null;
            for (java.io.File dir : dirs) {
                if (!dir.isDirectory()) continue;
                if (dir.getName().length() < 10) continue;
                java.io.File dbFile = new java.io.File(dir, "EnMicroMsg.db");
                if (!dbFile.exists()) continue;

                try {
                    Class<?> wo = com.leshao.v3.ContextManager.getClassLoader()
                            .loadClass("wo.w0");
                    Method g = wo.getDeclaredMethod("g", boolean.class);
                    String dbName = (String) g.invoke(null, true);
                    Method bMtd = wo.getDeclaredMethod("b", String.class, String.class, int.class);
                    byte[] pwd = (byte[]) bMtd.invoke(null, String.valueOf(uin), "ABCDEF", 0);
                    if (pwd == null) continue;

                    java.io.File dbPath = new java.io.File(dbFile.getAbsolutePath());
                    Class<?> sqliteClass = Class.forName("com.tencent.wcdb.database.SQLiteCipherSpec");
                    Object cipher = sqliteClass.getConstructor(byte[].class, int.class, int.class, int.class)
                            .newInstance(pwd, 2048, pwd.length, 0);
                    Class<?> wcdbClass = Class.forName("com.tencent.wcdb.database.SQLiteDatabase");
                    Method openDb = wcdbClass.getMethod("openDatabase", String.class, byte[].class, sqliteClass, Class.forName("com.tencent.wcdb.database.SQLiteCipherSpec"));
                    Object db = openDb.invoke(null, dbPath.getAbsolutePath(), null, cipher, null);

                    if (db != null) {
                        try {
                            Method rawQuery = db.getClass().getMethod("rawQuery", String.class, String[].class);
                            Object cursor = rawQuery.invoke(db, "SELECT nickname FROM rcontact WHERE username=?", new String[]{wxid});
                            if (cursor != null) {
                                Method moveToFirst = cursor.getClass().getMethod("moveToFirst");
                                if ((Boolean) moveToFirst.invoke(cursor)) {
                                    Method getStr = cursor.getClass().getMethod("getString", int.class);
                                    String nick = (String) getStr.invoke(cursor, 0);
                                    cursor.getClass().getMethod("close").invoke(cursor);
                                    db.getClass().getMethod("close").invoke(db);
                                    if (nick != null && !nick.isEmpty()) return nick;
                                }
                                cursor.getClass().getMethod("close").invoke(cursor);
                            }
                        } catch (Throwable ignored) {}
                        db.getClass().getMethod("close").invoke(db);
                    }
                } catch (Throwable ignored) {}
                break;
            }
        } catch (Throwable ignored) {}
        return null;
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
}
