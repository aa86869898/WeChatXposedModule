package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.service.ActivationManager;
import com.leshao.v3.ui.widgets.M3Page;
import com.leshao.v3.ui.widgets.ModernButton;

import java.lang.reflect.Method;

/**
 * 个人中心页（v955 M3 重排）：用户信息大卡 + 管理员入口。
 * 业务逻辑（头像异步加载/DB 昵称回查/复制 wxid/管理员可见性）与原版完全一致。
 */
public class ProfilePageView {

    public static View create(Context ctx, Activity parentAct) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = M3Page.root(ctx);

        // ===== 用户信息卡片 =====
        root.addView(M3Page.section(ctx, "用户信息"));
        LinearLayout infoCard = M3Page.card(ctx);

        // 头像+昵称/ID 水平布局（M3 list item 双行结构）
        LinearLayout userRow = new LinearLayout(ctx);
        userRow.setOrientation(LinearLayout.HORIZONTAL);
        userRow.setGravity(Gravity.CENTER_VERTICAL);
        int p16 = dpInt(ctx, 16);
        userRow.setPadding(p16, dpInt(ctx, 14), p16, dpInt(ctx, 14));

        // 头像左侧（M3 56dp 圆形容器）
        ImageView avatar = new ImageView(ctx);
        int avatarSize = dpInt(ctx, 56);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        alp.setMarginEnd(dpInt(ctx, 16));
        avatar.setLayoutParams(alp);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setCornerRadius(avatarSize / 2f);
        avatarBg.setColor(AppColors.tertiaryContainer());
        avatar.setBackground(avatarBg);

        // 优先使用 AvatarHelper 多路径加载（内部含微信缓存/本地文件/CDN 兜底）
        int avatarSizePx = avatarSize;
        AvatarHelper.loadAvatarAsync(avatar, MainActivity.getUserWxid(), avatarSizePx, null);
        userRow.addView(avatar);

        // 昵称+wxid 右侧
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView nickTv = new TextView(ctx);
        nickTv.setText("加载中...");
        nickTv.setTextSize(20);
        nickTv.setTextColor(AppColors.onSurface());
        nickTv.setTypeface(null, Typeface.BOLD);
        nickTv.setSingleLine(true);
        nickTv.setPadding(0, 0, 0, dpInt(ctx, 6));
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
        wxidTv.setTextSize(14);
        wxidTv.setTextColor(AppColors.onSurfaceVariant());
        wxidTv.setSingleLine(true);
        wxidTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        wxidRow.addView(wxidTv);

        // M3 复制按钮（text button 风格）
        ModernButton copyBtn = new ModernButton(ctx, "复制", ModernButton.STYLE_TEXT);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-2, -2);
        copyLp.setMarginStart(dpInt(ctx, 8));
        copyBtn.setLayoutParams(copyLp);
        copyBtn.onClick(() -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData cd = android.content.ClipData.newPlainText("wxid", MainActivity.getUserWxid());
                cm.setPrimaryClip(cd);
                Toast.makeText(ctx, "微信ID已复制", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
        wxidRow.addView(copyBtn);

        textCol.addView(wxidRow);
        userRow.addView(textCol);
        infoCard.addView(userRow);

        infoCard.addView(M3Page.divider(ctx));

        // 微信号
        String alias = MainActivity.getUserAlias();
        if (alias != null && !alias.isEmpty() && !alias.equals(MainActivity.getUserWxid())) {
            infoCard.addView(M3Page.infoRow(ctx, "微信号", alias));
            infoCard.addView(M3Page.divider(ctx));
        }

        root.addView(infoCard);

        // 管理员配置入口 (仅管理员可见)
        String currentWxid = MainActivity.getUserWxid();
        if (currentWxid != null && ActivationManager.isAdmin(currentWxid)) {
            root.addView(M3Page.section(ctx, "管理员工具"));
            LinearLayout adminCard = M3Page.card(ctx);
            adminCard.addView(M3Page.clickRow(ctx, "🛡", "模块黑名单管理", "管理模块功能黑名单用户",
                    () -> SubPageActivity.open(parentAct, "管理员工具", 98)));
            root.addView(adminCard);
        }

        return M3Page.scroll(ctx, root);
    }

    private static int dpInt(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
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
}
