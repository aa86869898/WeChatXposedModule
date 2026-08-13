package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.IconLoader;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.Contact;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.service.ActivationManager;

import java.io.File;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity {

    private static final String TAG = "MainActivity";

    private static Dialog sActiveDialog;
    private static volatile long sLastOpenTime = 0;

    private static volatile String sUserNickname;
    private static volatile String sUserAlias;
    private static volatile String sUserWxid;
    private static volatile String sAvatarPath;
    private static String sVipLevel = "王者VIP";

    public static String getUserNickname() { return sUserNickname; }
    public static String getUserAlias() { return sUserAlias; }
    public static String getUserWxid() { return sUserWxid; }
    public static String getAvatarPath() { return sAvatarPath; }
    public static String getVipLevel() { return sVipLevel; }

    private static int dialogTheme() {
        return AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar;
    }

    private static final String[] ITEM_NAMES = {
        "联系人和群聊", "群管理助手", "聊天分组",
        "TTS语音播报", "红包转账", "数据备份", "通讯录日志"
    };
    private static final int[] ITEM_ICONS = {
        0x1F465, 0x1F6E1, 0x1F4CB,
        0x1F50A, 0x1F4B0, 0x1F4BE, 0x1F4CB
    };

    private static final int[] PAGE_IDS = {
        3, 4, 14, 8, 9, 12, 13
    };

    private static final Map<Integer, String> PAGE_FEATURES = new HashMap<>();
    static {
        PAGE_FEATURES.put(14, "聊天分组|标签分组|分组管理|标签管理|ChatGroup");
        PAGE_FEATURES.put(3, "通讯录导出|联系人变更日志|通讯录|联系人|防撤回|消息防撤回|语音转发|语音消息转发");
        PAGE_FEATURES.put(8, "语音播报|TTS播报|排版引擎|配音|API|Voice|间隔|熔断|消息类型|免打扰|安静时段|播报参数|音量|语速|音调|TTS|文字消息播报|语音消息播报|图片消息播报|播报发送人昵称|播报群聊消息|截断长文字");
        PAGE_FEATURES.put(9, "自动抢红包|秒抢|红包震动|响铃|红包提醒|转账收款|私聊红包|群聊红包|时间段过滤|延时抢红包|排除群列|目标群聊|播报金额|关键词过滤");
        PAGE_FEATURES.put(12, "消息导出|聊天备份|导出聊天|备份数据|查看记录|清除记录|数据备份|导出|自动每日备份|导入外部记录|通讯录变更|变更日志");
    }

    public static void open(Activity act) {
        long now = System.currentTimeMillis();
        if (now - sLastOpenTime < 2000) return;
        sLastOpenTime = now;
        loadUserInfoAsync();

        String currentWxid = ModuleConfig.getCurrentWxid();
        if (currentWxid != null && !currentWxid.isEmpty()
                && ActivationManager.isBlacklisted(currentWxid)
                && !ActivationManager.isAdmin(currentWxid)) {
            showBlacklistBlock(act);
            return;
        }

        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null || !prefs.getBoolean("ls_disclaimer_accepted", false)) {
            showDisclaimer(act);
        } else {
            showMainPanel(act);
        }
    }

    public static void show(Activity act) {
        open(act);
    }

    // ===== User Info Loading =====

    private static void loadUserInfoAsync() {
        Context ctx = ContextManager.getAppContext();
        if (ctx == null) { LogWriter.log(TAG, "getAppContext null"); return; }

        sUserWxid = findWxidFromPrefs(ctx);
        sUserNickname = findNicknameFromPrefs(ctx);
        sUserAlias = sUserWxid;
        if (sUserNickname == null || sUserNickname.isEmpty()) sUserNickname = sUserWxid;

        new Thread(() -> loadUserDetails(ctx), "leshao-userinfo").start();
    }

    private static void loadUserDetails(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv == null) { LogWriter.log(TAG, "default_uin null"); return; }
            long uin = Long.parseLong(uv.toString());
            LogWriter.log(TAG, "uin=" + uin);

            if (sUserWxid == null || sUserWxid.isEmpty()) {
                sUserWxid = findWxidFromPrefs(ctx);
                LogWriter.log(TAG, "wxid=" + sUserWxid);
            }

            if (sUserWxid != null && !sUserWxid.isEmpty()) {
                boolean found = false;
                try {
                    ClassLoader cl = ContextManager.getClassLoader();
                    if (cl != null) {
                        Object db = openDb(cl, uin);
                        if (db != null) {
                            try {
                                Method rawQuery = db.getClass().getMethod("rawQuery", String.class, String[].class);
                                Object cursor = rawQuery.invoke(db, "SELECT username, nickname, alias FROM rcontact WHERE username=?", new String[]{sUserWxid});
                                if (cursor != null) {
                                    Method moveToFirst = cursor.getClass().getMethod("moveToFirst");
                                    if ((Boolean) moveToFirst.invoke(cursor)) {
                                        Method getStr = cursor.getClass().getMethod("getString", int.class);
                                        sUserNickname = (String) getStr.invoke(cursor, 1);
                                        sUserAlias = (String) getStr.invoke(cursor, 2);
                                        found = true;
                                        LogWriter.log(TAG, "nick=" + sUserNickname + " alias=" + sUserAlias);
                                    }
                                    cursor.getClass().getMethod("close").invoke(cursor);
                                }
                            } catch (Throwable e) {
                                LogWriter.log(TAG, "DB query failed: " + e.getMessage());
                            } finally {
                                try { db.getClass().getMethod("close").invoke(db); } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (Throwable e) {
                    LogWriter.log(TAG, "DB open failed: " + e.getMessage());
                }

                if (!found) {
                    sUserNickname = findNicknameFromPrefs(ctx);
                    sUserAlias = sUserWxid;
                }
            }

            if (sUserNickname == null || sUserNickname.isEmpty()) {
                sUserNickname = sUserWxid;
            }

            sAvatarPath = findAvatarPath(sUserWxid);
        } catch (Throwable e) {
            LogWriter.log(TAG, "loadUserDetails error: " + e.getMessage());
        }
    }

    private static String findAvatarPath(String wxid) {
        if (wxid == null) return null;
        try {
            java.io.File baseDir = ContextManager.getAppContext().getFilesDir().getParentFile();
            java.io.File[] searchDirs = new java.io.File[] {
                baseDir,
                new java.io.File("/data/user/0/" + baseDir.getName()),
            };

            for (java.io.File dataDir : searchDirs) {
                java.io.File[] subDirs = dataDir.listFiles();
                if (subDirs == null) continue;
                for (java.io.File sub : subDirs) {
                    if (!sub.isDirectory()) continue;
                    String fn = sub.getName();
                    if (fn.length() < 10) continue;
                    java.io.File avatarDir = new java.io.File(sub, "avatar");
                    if (!avatarDir.isDirectory()) continue;
                    for (String ext : new String[]{"_hd.png", ".png", ".jpg"}) {
                        java.io.File avFile = new java.io.File(avatarDir, wxid + ext);
                        if (avFile.exists() && avFile.length() > 0) return avFile.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "findAvatarPath err: " + t.getMessage());
        }
        return null;
    }

    private static String findWxidFromPrefs(Context ctx) {
        String[] prefNames = {
            "system_config_prefs", "com.tencent.mm_preferences",
            "notify_sync_pref", "tinker_simulate", "auth_info_key_prefs",
            "app_brand_global_sp", "exdevice_pref",
        };
        String[] keyNames = {
            "login_weixin_username", "login_user_name", "last_login_username",
            "auth_uin", "username", "uin", "_auth_uin",
        };

        for (String pn : prefNames) {
            try {
                SharedPreferences p = ctx.getSharedPreferences(pn, 0);
                Map<String, ?> all = p.getAll();
                for (String key : keyNames) {
                    Object v = all.get(key);
                    if (v != null) {
                        String val = v.toString();
                        if (val.startsWith("wxid_")) {
                            return val;
                        }
                    }
                }
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    Object v = entry.getValue();
                    if (v == null) continue;
                    String val = v.toString();
                    if (val.startsWith("wxid_") && !val.contains("@")) {
                        return val;
                    }
                }
            } catch (Throwable e) {}
        }
        return null;
    }

    private static String findNicknameFromPrefs(Context ctx) {
        String[] prefNames = {
            "system_config_prefs", "com.tencent.mm_preferences",
            "auth_info_key_prefs",
        };
        String[] keyNames = {
            "login_user_name", "login_nick_name", "nick_name", "nickname",
            "last_login_nickname", "user_nickname", "display_name",
        };
        for (String pn : prefNames) {
            try {
                SharedPreferences p = ctx.getSharedPreferences(pn, 0);
                Map<String, ?> all = p.getAll();
                for (String key : keyNames) {
                    Object v = all.get(key);
                    if (v != null) {
                        String val = v.toString();
                        if (!val.isEmpty() && !val.startsWith("wxid_") && !isNumeric(val)) {
                            return val;
                        }
                    }
                }
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    Object v = entry.getValue();
                    if (v == null) continue;
                    String val = v.toString();
                    if (!val.isEmpty() && !val.startsWith("wxid_") && !isNumeric(val)
                        && val.length() >= 2 && val.length() <= 30
                        && !entry.getKey().toLowerCase().contains("avatar")) {
                        return val;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isNumeric(String s) {
        try { Long.parseLong(s); return true; } catch (Throwable t) { return false; }
    }

    private static Object openDb(ClassLoader cl, long uin) throws Exception {
        String imei = "1234567890ABCDEF";
        try {
            Class<?> wo = cl.loadClass("wo.w0");
            Method g = wo.getDeclaredMethod("g", boolean.class);
            String s = (String) g.invoke(null, true);
            if (s != null && !s.isEmpty() && !s.equals("1234567890ABCDEF")) imei = s;
        } catch (Throwable e) {}

        String password = md5(imei + uin).substring(0, 7);

        String base = null;
        try {
            Class<?> mp0b = cl.loadClass("mp0.b");
            base = (String) mp0b.getDeclaredMethod("X").invoke(null);
        } catch (Throwable e) {
            base = ContextManager.getAppContext().getFilesDir().getParentFile().getAbsolutePath() + "/";
        }

        String hash = null;
        try {
            Class<?> hm0b0 = cl.loadClass("hm0.b0");
            hash = (String) hm0b0.getDeclaredMethod("e", int.class).invoke(null, (int) uin);
        } catch (Throwable e) {
            hash = md5("mm" + uin);
        }

        String dbPath = base + hash + "/EnMicroMsg.db";
        LogWriter.log(TAG, "DB path=" + dbPath);

        Class<?> sqliteDB = cl.loadClass("com.tencent.wcdb.database.SQLiteDatabase");
        Class<?> cursorFactoryCls = cl.loadClass("com.tencent.wcdb.database.SQLiteDatabase$CursorFactory");
        byte[] pwdBytes = password.getBytes("UTF-8");

        try {
            Class<?> cipherClass = cl.loadClass("com.tencent.wcdb.database.SQLiteCipherSpec");
            Method openDb = sqliteDB.getMethod("openDatabase", String.class, String.class, cipherClass,
                cursorFactoryCls, int.class);
            return openDb.invoke(null, dbPath, password, null, null, 0);
        } catch (Exception e1) {
            LogWriter.log(TAG, "DB: CipherSpec failed (" + e1.getClass().getSimpleName() + "), trying byte[]");
        }

        try {
            Method openDb = sqliteDB.getMethod("openDatabase", String.class, byte[].class,
                cursorFactoryCls, int.class);
            return openDb.invoke(null, dbPath, pwdBytes, null, 0);
        } catch (NoSuchMethodException e2) {
            LogWriter.log(TAG, "DB: 4-arg byte[] not found, trying 5-arg");
        }

        try {
            Method openDb = sqliteDB.getMethod("openDatabase", String.class, byte[].class,
                cursorFactoryCls, int.class,
                cl.loadClass("com.tencent.wcdb.database.SQLiteDatabase$DatabaseErrorHandler"));
            return openDb.invoke(null, dbPath, pwdBytes, null, 0, null);
        } catch (NoSuchMethodException e3) {
            throw new Exception("No compatible openDatabase method found on wcdb");
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable e) { return input; }
    }

    // ===== Disclaimer Dialog =====

    // ===== 黑名单拦截 =====

    private static void showBlacklistBlock(Activity act) {
        dismissDialog();
        float d = act.getResources().getDisplayMetrics().density;
        Context ctx = act;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(CandyUi.pageGradient());
        root.setPadding(dp(d, 24), dp(d, 24), dp(d, 24), dp(d, 24));

        TextView iconTv = new TextView(ctx);
        iconTv.setText("\uD83D\uDD12");
        iconTv.setTextSize(52);
        iconTv.setGravity(Gravity.CENTER);
        root.addView(iconTv);

        TextView titleTv = new TextView(ctx);
        titleTv.setText("模块已被禁用");
        titleTv.setTextSize(18);
        titleTv.setTextColor(AppColors.accent());
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, dp(d, 10), 0, 0);
        root.addView(titleTv);

        TextView msgTv = new TextView(ctx);
        msgTv.setText("您已被管理员列入模块黑名单，当前微信无法使用乐少助手的任何功能，也无法进入任何功能页面。\n\n如有疑问请联系管理员解除限制。");
        msgTv.setTextSize(13);
        msgTv.setTextColor(AppColors.text2());
        msgTv.setGravity(Gravity.CENTER);
        msgTv.setLineSpacing(dp(d, 4), 1.2f);
        msgTv.setPadding(0, dp(d, 10), 0, 0);
        root.addView(msgTv);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, dialogTheme());
        b.setView(root);
        b.setCancelable(false);
        AlertDialog dlg = b.create();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        sActiveDialog = dlg;
        dlg.setOnDismissListener(ignored -> { if (sActiveDialog == dlg) sActiveDialog = null; });
        dlg.show();
    }

    private static void showDisclaimer(Activity act) {
        dismissDialog();
        float d = act.getResources().getDisplayMetrics().density;
        Context ctx = act;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        root.setPadding(dp(d, 12), dp(d, 12), dp(d, 12), dp(d, 12));

        TextView titleTv = new TextView(ctx);
        titleTv.setText("免责声明");
        titleTv.setTextSize(18);
        titleTv.setTextColor(AppColors.accent());
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, 0, 0, dp(d, 8));
        root.addView(titleTv);

        ScrollView sv = new ScrollView(ctx);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));
        LinearLayout bodyCol = new LinearLayout(ctx);
        bodyCol.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bodyBg = new GradientDrawable();
        bodyBg.setCornerRadius(dp(d, 8));
        bodyBg.setColor(AppColors.card());
        bodyCol.setBackground(bodyBg);
        bodyCol.setPadding(dp(d, 12), dp(d, 10), dp(d, 12), dp(d, 10));

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        appendPara(ssb, "用户在使用本工具前，须完整阅读、充分理解并自愿同意本全部免责条款，开启及使用本软件即代表本人已完整阅读、完全知晓并自愿接受所有协议内容。");
        appendPara(ssb, "乐少助手为完全免费的个人技术学习工具，面向所有用户免费使用。平台所有捐赠通道均为用户自愿支持行为，纯属个人心意赞助，不属于软件收费、功能购买、售后担保服务，捐赠与否不影响软件完整功能的正常使用。");
        appendPara(ssb, "本工具依据《计算机软件保护条例》第十七条，仅供个人Android技术学习、开发研究、技术测试使用，仅可在本人持有完全使用权的设备上运行。本工具所有用户配置、任务数据、操作记录均仅在用户设备本地存储，不会私自收集、上传、泄露用户任何隐私数据与账号信息。");
        appendPara(ssb, "本模块纯属个人技术学习作品，与腾讯公司及微信官方无任何合作、授权、关联关系。使用本工具可能存在违反对应平台用户协议的风险，可能导致账号限制、功能受限或封禁，所有风险由使用者自行预判并承担。");
        appendBold(ssb, "严禁私自贩卖、倒卖、二次打包、商用分发本软件及相关衍生资源，严禁用于批量营销、骚扰引流、违规牟利、侵权破坏等违规违法场景。使用者需遵守国家法律法规，一切不当使用造成的账号后果、法律责任均由使用者自行承担，开发者不承担任何连带责任，亦不提供规避风控相关技术支持。");

        TextView bodyTv = new TextView(ctx);
        bodyTv.setText(ssb);
        bodyTv.setTextSize(13);
        bodyTv.setTextColor(AppColors.text1());
        bodyTv.setLineSpacing(dp(d, 4), 1.2f);
        bodyCol.addView(bodyTv);
        sv.addView(bodyCol);
        root.addView(sv);

        root.addView(candyDivider(ctx, d));

        CheckBox checkBox = new CheckBox(ctx);
        checkBox.setText("我已完整阅读并同意以上免责条款");
        checkBox.setTextSize(13);
        checkBox.setTextColor(AppColors.text1());
        checkBox.setPadding(0, 0, 0, dp(d, 2));
        root.addView(checkBox);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button declineBtn = new Button(ctx);
        declineBtn.setText("不同意");
        declineBtn.setTextSize(14);
        declineBtn.setTextColor(AppColors.text2());
        declineBtn.setAllCaps(false);
        GradientDrawable declineBg = new GradientDrawable();
        declineBg.setCornerRadius(dp(d, 8));
        declineBg.setColor(AppColors.card());
        declineBtn.setBackground(declineBg);
        declineBtn.setPadding(dp(d, 16), dp(d, 10), dp(d, 16), dp(d, 10));
        declineBtn.setPaintFlags(declineBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        declineBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        btnRow.addView(declineBtn);

        btnRow.addView(spacerH(ctx, d, 10));

        Button agreeBtn = new Button(ctx);
        agreeBtn.setText("同意并继续 (30秒)");
        agreeBtn.setTextSize(14);
        agreeBtn.setTextColor(Color.WHITE);
        agreeBtn.setAllCaps(false);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(d, 8));
        btnBg.setColor(AppColors.offColor());
        agreeBtn.setBackground(btnBg);
        agreeBtn.setPadding(dp(d, 16), dp(d, 10), dp(d, 16), dp(d, 10));
        agreeBtn.setEnabled(false);
        agreeBtn.setPaintFlags(agreeBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        agreeBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        btnRow.addView(agreeBtn);
        root.addView(btnRow);

        Handler handler = new Handler(Looper.getMainLooper());
        final int[] remaining = {30};
        Runnable countdown = new Runnable() {
            @Override
            public void run() {
                if (remaining[0] <= 0) {
                    agreeBtn.setText("同意并继续");
                    agreeBtn.setEnabled(true);
                    GradientDrawable activeBg = new GradientDrawable();
                    activeBg.setCornerRadius(dp(d, 8));
                    activeBg.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
                    activeBg.setColors(new int[]{AppColors.accent2(), AppColors.accent()});
                    agreeBtn.setBackground(activeBg);
                    return;
                }
                agreeBtn.setText("同意并继续 (" + remaining[0] + "秒)");
                remaining[0]--;
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(countdown);

        final AlertDialog[] dlRef = new AlertDialog[1];
        declineBtn.setOnClickListener(v -> {
            handler.removeCallbacks(countdown);
            if (dlRef[0] != null) dlRef[0].dismiss();
            act.finish();
        });

        agreeBtn.setOnClickListener(v -> {
            if (!checkBox.isChecked()) {
                Toast.makeText(ctx, "请先阅读并勾选同意条款", Toast.LENGTH_SHORT).show();
                return;
            }
            handler.removeCallbacks(countdown);
            SharedPreferences prefs = ContextManager.getPrefs();
            if (prefs != null) {
                prefs.edit().putBoolean("ls_disclaimer_accepted", true).apply();
            }
            if (dlRef[0] != null) dlRef[0].dismiss();
            showMainPanel(act);
        });

        AlertDialog dl = new AlertDialog.Builder(ctx, dialogTheme())
            .setView(root)
            .setCancelable(false)
            .create();
        dlRef[0] = dl;
        sActiveDialog = dl;
        dl.setOnDismissListener(ignored -> { if (sActiveDialog == dl) sActiveDialog = null; });
        dl.show();

        Window w = dl.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
        }
    }


    private static void appendPara(SpannableStringBuilder ssb, String text) {
        if (ssb.length() > 0) ssb.append("\n\n");
        ssb.append(text);
    }

    private static void appendBold(SpannableStringBuilder ssb, String text) {
        if (ssb.length() > 0) ssb.append("\n\n");
        int start = ssb.length();
        ssb.append(text);
        ssb.setSpan(new StyleSpan(Typeface.BOLD), start, ssb.length(), 0);
    }

    // ===== Main Panel Dialog =====

    private static void showMainPanel(Activity act) {
        dismissDialog();

        float d = act.getResources().getDisplayMetrics().density;
        Context ctx = act;

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());

        root.addView(buildTopBar(ctx, d, act));
        root.addView(candyDivider(ctx, d));
        View searchCard = buildSearchCard(ctx, d);
        root.addView(searchCard);
        root.addView(candyDivider(ctx, d));

        LinearLayout card1 = buildCard(ctx, d);
        card1.addView(makeListRow(ctx, d, 0x2764, "爱心捐赠", AppColors.accent(), true, v -> showDonateDialog(act)));
        card1.addView(candyDivider(ctx, d));
        card1.addView(makeListRow(ctx, d, 0x1F464, "个人中心", 0, false, v -> {
            dismissDialog();
            SubPageActivity.openFromMain(act, "个人中心", 99);
        }));
        root.addView(card1);

        root.addView(candyDivider(ctx, d));

        LinearLayout card2 = buildCard(ctx, d);
        final HashMap<View, String> searchMap = new HashMap<>();
        boolean first = true;
        for (int i = 0; i < ITEM_NAMES.length; i++) {
            if (!first) card2.addView(candyDivider(ctx, d));
            first = false;
            final int idx = i;
            final int pageId = PAGE_IDS[i];

            View item = makeListRow(ctx, d, ITEM_ICONS[i], ITEM_NAMES[i], 0, false, v -> {
                dismissDialog();
                SubPageActivity.openFromMain(act, ITEM_NAMES[idx], pageId);
            });
            item.setTag("menu_item");
            String features = PAGE_FEATURES.get(pageId);
            String searchText = ITEM_NAMES[i] + (features != null ? "|" + features : "");
            searchMap.put(item, searchText);
            card2.addView(item);
        }
        root.addView(card2);

        root.addView(candyDivider(ctx, d));

        sv.addView(root);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, dialogTheme());
        b.setView(sv);
        b.setCancelable(true);
        AlertDialog dlg = b.create();

        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(-1, -1);
            w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        sActiveDialog = dlg;
        dlg.setOnDismissListener(ignored -> { if (sActiveDialog == dlg) sActiveDialog = null; });

        EditText searchBox = (EditText) searchCard.findViewWithTag("search_box");
        setupSearch(searchBox, searchMap, card2);

        dlg.show();
    }

    // ===== Top Bar =====

    private static View buildTopBar(Context ctx, float d, Activity act) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(d, 16), dp(d, 12), dp(d, 16), dp(d, 12));
        bar.setBackgroundColor(AppColors.accent());

        TextView titleTv = new TextView(ctx);
        titleTv.setText("乐少助手 " + ContextManager.getVersionName());
        titleTv.setTextSize(17);
        titleTv.setTextColor(AppColors.WHITE_TEXT);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        bar.addView(titleTv);

        return bar;
    }

    // ===== Search Card =====

    private static View buildSearchCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(d, 12), 0, dp(d, 12), 0);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(d, 8));
        cardBg.setColor(AppColors.card());
        card.setBackground(cardBg);

        EditText searchBox = new EditText(ctx);
        searchBox.setHint("搜索模块功能...");
        searchBox.setTextSize(14);
        searchBox.setTextColor(AppColors.text1());
        searchBox.setHintTextColor(AppColors.text2());
        searchBox.setSingleLine(true);
        searchBox.setBackground(CandyUi.inputBg(ctx));
        searchBox.setPadding(0, dp(d, 10), 0, dp(d, 10));
        searchBox.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        searchBox.setTag("search_box");
        card.addView(searchBox);
        card.setTag("search_card");

        return card;
    }

    private static void setupSearch(final EditText searchBox,
                                      final HashMap<View, String> searchMap,
                                      final LinearLayout itemsContainer) {
        if (searchBox == null) return;

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int cnt, int aft) {}
            @Override public void onTextChanged(CharSequence s, int st, int bef, int cnt) {}
            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase();
                for (Map.Entry<View, String> entry : searchMap.entrySet()) {
                    View menuItem = entry.getKey();
                    String searchText = entry.getValue().toLowerCase();
                    menuItem.setVisibility(query.isEmpty() || searchText.contains(query) ? View.VISIBLE : View.GONE);
                }
                for (int i = 0; i < itemsContainer.getChildCount(); i++) {
                    View child = itemsContainer.getChildAt(i);
                    if ("menu_item".equals(child.getTag())) continue;
                    child.setVisibility(View.GONE);
                    for (int j = i + 1; j < itemsContainer.getChildCount(); j++) {
                        View next = itemsContainer.getChildAt(j);
                        if ("menu_item".equals(next.getTag())) {
                            if (next.getVisibility() == View.VISIBLE && query.isEmpty())
                                child.setVisibility(View.VISIBLE);
                            break;
                        }
                    }
                }
            }
        });
    }

    // ===== Card Containers =====

    private static LinearLayout buildCard(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(d, 12), 0, dp(d, 12), 0);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(d, 8));
        cardBg.setColor(AppColors.card());
        card.setBackground(cardBg);
        return card;
    }

    // ===== List Row =====

    private static View makeListRow(Context ctx, float d, int emoji, String title,
                                      int textColor, boolean bold,
                                      View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(d, 16), dp(d, 13), dp(d, 16), dp(d, 13));
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setOnClickListener(onClick);
        row.setClickable(true);

        TextView icon = new TextView(ctx);
        icon.setText(new String(Character.toChars(emoji)));
        icon.setTextSize(18);
        icon.setPadding(0, 0, dp(d, 12), 0);
        row.addView(icon);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(textColor != 0 ? textColor : AppColors.text1());
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(tv);

        TextView arrow = new TextView(ctx);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(AppColors.arrow());
        row.addView(arrow);

        return row;
    }

    private static View makeInnerDivider(Context ctx, float d) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins(dp(d, 52), 0, 0, 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(AppColors.divider());
        return v;
    }

    // ===== Donate Dialog =====

    private static void showDonateDialog(Activity act) {
        float d = act.getResources().getDisplayMetrics().density;
        Context ctx = act;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        root.setPadding(dp(d, 20), dp(d, 20), dp(d, 20), dp(d, 16));

        TextView titleTv = new TextView(ctx);
        titleTv.setText("爱心捐赠");
        titleTv.setTextSize(20);
        titleTv.setTextColor(AppColors.accent());
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        titleTv.setPadding(0, 0, 0, dp(d, 16));
        root.addView(titleTv);

        LinearLayout card1 = new LinearLayout(ctx);
        card1.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable card1Bg = new GradientDrawable();
        card1Bg.setCornerRadius(dp(d, 8));
        card1Bg.setColor(AppColors.card());
        card1.setBackground(card1Bg);
        card1.setPadding(dp(d, 12), dp(d, 10), dp(d, 12), dp(d, 10));

        card1.addView(buildDonateButtons(ctx, d, act));
        root.addView(card1);
        root.addView(candyDivider(ctx, d));

        LinearLayout card2 = new LinearLayout(ctx);
        card2.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable card2Bg = new GradientDrawable();
        card2Bg.setCornerRadius(dp(d, 8));
        card2Bg.setColor(AppColors.card());
        card2.setBackground(card2Bg);
        card2.setPadding(dp(d, 16), dp(d, 14), dp(d, 16), dp(d, 14));

        TextView contactBtn = new TextView(ctx);
        contactBtn.setText("联系乐少");
        contactBtn.setTextSize(15);
        contactBtn.setTextColor(Color.WHITE);
        contactBtn.setTypeface(null, Typeface.BOLD);
        contactBtn.setGravity(Gravity.CENTER);
        contactBtn.setPadding(dp(d, 14), dp(d, 12), dp(d, 14), dp(d, 12));
        GradientDrawable cbBg = new GradientDrawable();
        cbBg.setCornerRadius(dp(d, 8));
        cbBg.setColor(AppColors.accent());
        contactBtn.setBackground(cbBg);
        contactBtn.setOnClickListener(cv -> {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.tencent.mm", "com.tencent.mm.plugin.webview.ui.tools.WebViewUI");
                intent.putExtra("rawUrl", "https://work.weixin.qq.com/ca/cawcde22ff06beab20");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(intent);
            } catch (Throwable e) {
                try {
                    Intent fallback = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://work.weixin.qq.com/ca/cawcde22ff06beab20"));
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(fallback);
                } catch (Throwable ignored) {}
            }
        });
        contactBtn.setPaintFlags(contactBtn.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        card2.addView(contactBtn);
        root.addView(card2);

        AlertDialog dlg = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(root)
            .setCancelable(true)
            .create();
        dlg.show();
    }

    private static View buildDonateButtons(Context ctx, float d, Activity act) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        View aliBtn = buildDonateBtn(ctx, d, act, "donate_alipay", "支付宝打赏");
        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(d, 12), 0));
        View wxBtn = buildDonateBtn(ctx, d, act, "donate_wechat", "微信打赏");

        row.addView(aliBtn);
        row.addView(spacer);
        row.addView(wxBtn);
        return row;
    }

    private static View buildDonateBtn(Context ctx, float d, Activity act, String resName, String label) {
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(d, 8), dp(d, 6), dp(d, 8), dp(d, 6));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(d, 6));
        btnBg.setColor(AppColors.bg());
        btn.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        btn.setLayoutParams(btnLp);
        btn.setClickable(true);
        btn.setOnClickListener(v -> showDonateImage(ctx, d, act, resName, label));

        android.graphics.drawable.Drawable thumb = loadModuleDrawable(ctx, resName);
        if (thumb != null) {
            ImageView img = new ImageView(ctx);
            img.setImageDrawable(thumb);
            img.setAdjustViewBounds(true);
            img.setMaxWidth(dp(d, 130));
            img.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(d, 140)));
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            btn.addView(img);
        }

        TextView labelTv = new TextView(ctx);
        labelTv.setText(label);
        labelTv.setTextSize(13);
        labelTv.setTextColor(AppColors.accent());
        labelTv.setTypeface(null, Typeface.BOLD);
        labelTv.setGravity(Gravity.CENTER);
        labelTv.setPadding(0, dp(d, 4), 0, 0);
        btn.addView(labelTv);

        return btn;
    }

    private static void showDonateImage(Context ctx, float d, Activity act, String resName, String title) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.pageGradient());
        root.setPadding(dp(d, 20), dp(d, 20), dp(d, 20), dp(d, 16));
        root.setGravity(Gravity.CENTER);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(18);
        tv.setTextColor(AppColors.accent());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 0, 0, dp(d, 14));
        root.addView(tv);

        android.graphics.drawable.Drawable full = loadModuleDrawable(ctx, resName);
        if (full != null) {
            ImageView img = new ImageView(ctx);
            img.setImageDrawable(full);
            img.setAdjustViewBounds(true);
            img.setMaxWidth(dp(d, 300));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.CENTER;
            img.setLayoutParams(lp);
            root.addView(img);
        }

        AlertDialog dlg = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(root)
            .setCancelable(true)
            .create();
        dlg.show();
    }

    // ===== Public Static Utilities (used by other classes) =====

    public static void dismissDialog() {
        if (sActiveDialog != null && sActiveDialog.isShowing()) {
            try { sActiveDialog.dismiss(); } catch (Throwable ignored) {}
        }
        sActiveDialog = null;
    }

    public static android.graphics.drawable.Drawable loadModuleDrawable(Context ctx, String name) {
        try {
            Resources res = IconLoader.moduleResources(ctx);
            if (res == null) return null;
            int resId = res.getIdentifier(name, "drawable", "com.leshao.v3");
            if (resId != 0) {
                return res.getDrawable(resId);
            }
        } catch (Throwable t) {
            LogWriter.log("MainActivity", "loadModuleDrawable(" + name + ") err: " + t.getMessage());
        }
        return null;
    }

    public static View makeTitleBar(Context ctx, String title, boolean showBack, Runnable onBack) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(d, 12), dp(d, 10), dp(d, 12), dp(d, 10));
        GradientDrawable barBg = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.accent2(), AppColors.accent()});
        bar.setBackground(barBg);

        if (showBack) {
            TextView back = new TextView(ctx);
            back.setText("< 返回");
            back.setTextSize(13);
            back.setTextColor(AppColors.whiteCard());
            back.setPadding(0, 0, dp(d, 8), 0);
            back.setPaintFlags(back.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            back.setOnClickListener(v -> { if (onBack != null) onBack.run(); });
            bar.addView(back);
        }

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(17);
        tv.setTextColor(AppColors.whiteCard());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        bar.addView(tv);

        if (showBack) {
            View spacer = new View(ctx);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 0));
            bar.addView(spacer);
        }

        return bar;
    }

    static View makeDivider(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(d, 6)));
        v.setBackgroundColor(AppColors.bg());
        return v;
    }

    // ===== Internal Utilities =====

    private static int dp(float density, int dp) {
        return (int)(dp * density + 0.5f);
    }

    private static View spacerV(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(d, dp)));
        return v;
    }

    private static View spacerH(Context ctx, float d, int dp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(d, dp), 0));
        return v;
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
