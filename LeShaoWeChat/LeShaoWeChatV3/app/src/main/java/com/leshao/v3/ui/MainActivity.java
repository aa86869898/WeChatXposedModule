package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.model.Contact;

import java.io.File;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

public class MainActivity {

    private static final String TAG = "MainActivity";

    private static AlertDialog sActiveDialog;
    private static volatile long sLastOpenTime = 0;

    private static String sUserNickname;
    private static String sUserAlias;
    private static String sUserWxid;
    private static String sAvatarPath;
    private static String sVipLevel = "王者VIP";

    public static void open(Activity act) {
        long now = System.currentTimeMillis();
        if (now - sLastOpenTime < 2000) return;
        sLastOpenTime = now;
        loadUserInfo();
        showMainPanel(act);
    }

    private static void loadUserInfo() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) { LogWriter.log(TAG, "getAppContext null"); return; }

            // 1. 取 uin
            SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv == null) { LogWriter.log(TAG, "default_uin null"); return; }
            long uin = Long.parseLong(uv.toString());
            LogWriter.log(TAG, "uin=" + uin);

            // 2. 搜 wxid: 遍历多个 SharedPreferences
            sUserWxid = findWxidFromPrefs(ctx);
            LogWriter.log(TAG, "wxid=" + sUserWxid);

            // 3. 获取原始昵称 + alias（分开存储，不用 displayName 优先级合并）
            if (sUserWxid != null && !sUserWxid.isEmpty()) {
                boolean found = false;
                // 优先从 DB 直接查（获取 raw nickname 和 alias）
                try {
                    ClassLoader cl = ContextManager.getClassLoader();
                    Object db = openDb(cl, uin);
                    if (db != null) {
                        Method u = db.getClass().getDeclaredMethod("u", String.class, String[].class);
                        android.database.Cursor c = (android.database.Cursor) u.invoke(db,
                            "SELECT nickname, alias FROM rcontact WHERE username='" + sUserWxid + "' AND deleteFlag=0", null);
                        if (c != null && c.moveToFirst()) {
                            sUserNickname = c.getString(c.getColumnIndex("nickname"));
                            sUserAlias = c.getString(c.getColumnIndex("alias"));
                            LogWriter.log(TAG, "raw nickname=" + sUserNickname + " alias=" + sUserAlias);
                            found = true;
                            c.close();
                        }
                        db.getClass().getMethod("c").invoke(db);
                    }
                } catch (Throwable e) { LogWriter.log(TAG, "db query err: " + e.getMessage()); }

                // 回退：从 ContactRepository 取
                if (!found) {
                    try {
                        List<Contact> all = ContactRepository.getAll();
                        if (all != null) {
                            for (Contact c : all) {
                                if (sUserWxid.equals(c.wxid)) {
                                    sUserNickname = c.nickname;
                                    sUserAlias = c.alias;
                                    found = true;
                                    LogWriter.log(TAG, "nickname from repo=" + sUserNickname);
                                    break;
                                }
                            }
                        }
                    } catch (Throwable e) { LogWriter.log(TAG, "ContactRepository lookup err: " + e.getMessage()); }
                }

                // 回退：从 SharedPreferences 找
                if (!found || sUserNickname == null || sUserNickname.isEmpty()) {
                    sUserNickname = findNicknameFromPrefs(ctx);
                }
            }

            // 4. 头像路径
            if (sUserWxid != null && !sUserWxid.isEmpty()) {
                ClassLoader cl = ContextManager.getClassLoader();
                sAvatarPath = getAvatarPath(cl, ctx, uin, sUserWxid);
            }

            // 5. 账号信息（替代旧的激活码有效期）
            if (sUserAlias != null && !sUserAlias.isEmpty()) {
                sUserAlias = sUserAlias;
            } else {
                sUserAlias = sUserWxid;
            }

            if (sUserNickname == null || sUserNickname.isEmpty()) {
                sUserNickname = sUserWxid != null ? sUserWxid : "微信用户";
            }

        } catch (Throwable e) {
            LogWriter.log(TAG, "loadUserInfo error: " + e.getMessage());
        }
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
                // 先按已知 key 查找
                for (String key : keyNames) {
                    Object v = all.get(key);
                    if (v != null) {
                        String val = v.toString();
                        LogWriter.log(TAG, "  " + pn + "/" + key + "=" + val);
                        if (val.startsWith("wxid_")) {
                            return val;
                        }
                    }
                }
                // 遍历所有 key，找 wxid_ 开头的值
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    Object v = entry.getValue();
                    if (v == null) continue;
                    String val = v.toString();
                    if (val.startsWith("wxid_") && !val.contains("@")) {
                        LogWriter.log(TAG, "  found wxid via scan in " + pn + " key=" + entry.getKey());
                        return val;
                    }
                }
            } catch (Throwable e) { LogWriter.log(TAG, "  skip " + pn + ": " + e.getMessage()); }
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
        };
        for (String pn : prefNames) {
            try {
                SharedPreferences p = ctx.getSharedPreferences(pn, 0);
                for (String key : keyNames) {
                    Object v = p.getAll().get(key);
                    if (v != null) {
                        String val = v.toString();
                        if (!val.isEmpty() && !val.startsWith("wxid_") && !isNumeric(val)) {
                            return val;
                        }
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
        // 密码计算
        String imei = "1234567890ABCDEF";
        try {
            Class<?> wo = cl.loadClass("wo.w0");
            Method g = wo.getDeclaredMethod("g", boolean.class);
            String s = (String) g.invoke(null, true);
            if (s != null && !s.isEmpty() && !s.equals("1234567890ABCDEF")) imei = s;
        } catch (Throwable e) {}

        String password = md5(imei + uin).substring(0, 7);

        // 路径计算
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

        String dbPath = base + "MicroMsg/" + hash + "/EnMicroMsg.db";

        try {
            Class<?> ka5f = cl.loadClass("ka5.f");
            Method s = ka5f.getDeclaredMethod("s", String.class, String.class, int.class, boolean.class);
            return s.invoke(null, dbPath, password, 0, true);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String getAvatarPath(ClassLoader cl, Context ctx, long uin, String wxid) {
        try {
            String base = null;
            try {
                Class<?> mp0b = cl.loadClass("mp0.b");
                base = (String) mp0b.getDeclaredMethod("X").invoke(null);
            } catch (Throwable e) {
                base = ctx.getFilesDir().getParentFile().getAbsolutePath() + "/";
            }

            String hash = null;
            try {
                Class<?> hm0b0 = cl.loadClass("hm0.b0");
                hash = (String) hm0b0.getDeclaredMethod("e", int.class).invoke(null, (int) uin);
            } catch (Throwable e) {
                hash = md5("mm" + uin);
            }

            String accountDir = base + "MicroMsg/" + hash + "/";
            String md5 = md5(wxid);
            return accountDir + "avatar/" + md5.substring(0, 2) + "/"
                + md5.substring(2, 4) + "/user_" + md5 + ".png";
        } catch (Throwable e) {
            return null;
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }

    // ===== UI =====

    // 模块列表数据
    private static final String[] ITEM_NAMES = {
        "聊天功能", "主题美化", "联系人和群聊", "群管理助手", "万群自动转发",
        "定时消息助手", "AI智慧助手", "TTS播报转语音",
        "红包转账", "朋友圈增强", "隐私安全",
        "数据备份", "娱乐助手", "捐赠支持开发"
    };
    private static final int[] ITEM_ICONS = {
        0x1F4AC, 0x1F3A8, 0x1F465, 0x1F6E1, 0x1F4E4,
        0x23F0, 0x1F916, 0x1F50A,
        0x1F4B0, 0x1F4F1, 0x1F512,
        0x1F4BE, 0x1F3AE, 0x2764
    };

    private static void showMainPanel(Activity act) {
        dismissDialog();

        float d = act.getResources().getDisplayMetrics().density;
        Context ctx = act;

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());

        // 标题栏
        root.addView(makeTitleBar(ctx, "乐少多功能助手", false, null));

        // 搜索框
        EditText searchBox = new EditText(ctx);
        searchBox.setHint("搜索模块功能...");
        searchBox.setTextSize(14);
        searchBox.setTextColor(AppColors.text1());
        searchBox.setHintTextColor(AppColors.text2());
        searchBox.setBackgroundColor(AppColors.card());
        searchBox.setPadding((int)(16 * d), (int)(5 * d), (int)(16 * d), (int)(5 * d));
        searchBox.setSingleLine(true);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setCornerRadius((int)(8 * d));
        searchBg.setColor(AppColors.card());
        searchBox.setBackground(searchBg);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
        searchLp.setMargins((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(6 * d));
        searchBox.setLayoutParams(searchLp);
        root.addView(searchBox);

        // 分割线
        root.addView(makeDivider(ctx));

        // 菜单列表容器
        LinearLayout itemsContainer = new LinearLayout(ctx);
        itemsContainer.setOrientation(LinearLayout.VERTICAL);

        // 构建完整列表
        for (int i = 0; i < ITEM_NAMES.length; i++) {
            if (i > 0) itemsContainer.addView(makeItemDivider(ctx));
            final int idx = i;
            View item = makeMenuItem(ctx, d, ITEM_NAMES[i], ITEM_ICONS[i], v -> {
                dismissDialog();
                SubPageActivity.open(act, ITEM_NAMES[idx], idx + 1);
            });
            item.setTag("menu_item");
            itemsContainer.addView(item);
        }
        root.addView(itemsContainer);

        // 底部间距
        root.addView(spacerV(ctx, 16));

        // 搜索过滤逻辑
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int cnt, int aft) {}
            @Override public void onTextChanged(CharSequence s, int st, int bef, int cnt) {}
            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase();
                boolean anyVisible = false;
                for (int i = 0; i < itemsContainer.getChildCount(); i++) {
                    View child = itemsContainer.getChildAt(i);
                    Object tag = child.getTag();
                    if ("menu_item".equals(tag)) {
                        String name = ITEM_NAMES[i / 2 >= ITEM_NAMES.length ? 0 : i / 2];
                        if (query.isEmpty() || name.contains(query) || name.toLowerCase().contains(query)) {
                            child.setVisibility(View.VISIBLE);
                            anyVisible = true;
                        } else {
                            child.setVisibility(View.GONE);
                        }
                    }
                }
                // 同步隐藏/显示分隔线
                boolean prevVisible = false;
                for (int i = 0; i < itemsContainer.getChildCount(); i++) {
                    View child = itemsContainer.getChildAt(i);
                    if ("menu_item".equals(child.getTag())) {
                        if (child.getVisibility() == View.VISIBLE) {
                            prevVisible = true;
                        }
                    }
                }
                // 简化分隔线处理: 有搜索时隐藏全部分隔线, 无搜索时恢复
                for (int i = 0; i < itemsContainer.getChildCount(); i++) {
                    View child = itemsContainer.getChildAt(i);
                    if (!"menu_item".equals(child.getTag())) {
                        // 找下一个可见的 menu_item
                        View nextItem = null;
                        for (int j = i + 1; j < itemsContainer.getChildCount(); j++) {
                            if ("menu_item".equals(itemsContainer.getChildAt(j).getTag())) {
                                nextItem = itemsContainer.getChildAt(j);
                                break;
                            }
                        }
                        child.setVisibility(nextItem != null && nextItem.getVisibility() == View.VISIBLE
                                && (query.isEmpty()) ? View.VISIBLE : View.GONE);
                    }
                }
            }
        });

        sv.addView(root);

        AlertDialog.Builder b = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setView(sv);
        b.setCancelable(true);
        AlertDialog dlg = b.create();
        sActiveDialog = dlg;

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int)(ctx.getResources().getDisplayMetrics().widthPixels * 0.90),
                        (int)(ctx.getResources().getDisplayMetrics().heightPixels * 0.82));
            w.setGravity(Gravity.CENTER);
        }
        dlg.show();
    }

    // ===== 组件工厂 =====

    public static View makeTitleBar(Context ctx, String title, boolean showBack, Runnable onBack) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int)(12 * d), (int)(10 * d), (int)(12 * d), (int)(10 * d));
        GradientDrawable barBg = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{AppColors.accent2(), AppColors.accent()});
        bar.setBackground(barBg);

        if (showBack) {
            TextView back = new TextView(ctx);
            back.setText("< 返回");
            back.setTextSize(13);
            back.setTextColor(AppColors.whiteCard());
            back.setPadding(0, 0, (int)(8 * d), 0);
            back.setOnClickListener(v -> {
                if (onBack != null) onBack.run();
            });
            bar.addView(back);
        }

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(17);
        tv.setTextColor(AppColors.whiteCard());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tv.setLayoutParams(tvlp);
        bar.addView(tv);

        if (showBack) {
            View spacer = new View(ctx);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 0));
            bar.addView(spacer);
        }

        return bar;
    }

    private static View makeUserHeader(Context ctx, float d) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding((int)(16 * d), (int)(14 * d), (int)(16 * d), (int)(14 * d));
        card.setBackgroundColor(AppColors.card());

        // 头像
        ImageView avatar = new ImageView(ctx);
        int avatarSize = (int)(50 * d);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        alp.setMargins(0, 0, (int)(14 * d), 0);
        avatar.setLayoutParams(alp);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);

        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setCornerRadius(avatarSize / 2f);
        avatarBg.setColor(0xFFE8D8F0);
        avatar.setBackground(avatarBg);

        if (sAvatarPath != null) {
            File f = new File(sAvatarPath);
            if (f.exists()) {
                Bitmap bm = BitmapFactory.decodeFile(sAvatarPath);
                if (bm != null) avatar.setImageBitmap(bm);
            }
        }
        card.addView(avatar);

        // 右侧信息
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        // 第一行: 昵称 + VIP 标签
        LinearLayout nameRow = new LinearLayout(ctx);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView nameTv = new TextView(ctx);
        nameTv.setText(sUserNickname != null ? sUserNickname : "微信用户");
        nameTv.setTextSize(12);
        nameTv.setTextColor(AppColors.text1());
        nameTv.setTypeface(null, Typeface.BOLD);
        nameRow.addView(nameTv);

        // VIP 等级标签
        TextView vipTv = new TextView(ctx);
        vipTv.setText(sVipLevel);
        vipTv.setTextSize(10);
        vipTv.setTextColor(AppColors.whiteCard());
        vipTv.setPadding((int)(6 * d), (int)(2 * d), (int)(6 * d), (int)(2 * d));
        GradientDrawable vipBg = new GradientDrawable();
        vipBg.setCornerRadius((int)(4 * d));
        vipBg.setColor(0xFFE04040);
        vipTv.setBackground(vipBg);
        LinearLayout.LayoutParams vipLp = new LinearLayout.LayoutParams(-2, -2);
        vipLp.setMargins((int)(8 * d), 0, 0, 0);
        vipTv.setLayoutParams(vipLp);
        nameRow.addView(vipTv);

        info.addView(nameRow);

        // 第二行: 微信ID
        TextView wxidTv = new TextView(ctx);
        wxidTv.setText("微信ID: " + (sUserWxid != null ? sUserWxid : ""));
        wxidTv.setTextSize(12);
        wxidTv.setTextColor(AppColors.text2());
        wxidTv.setPadding(0, (int)(3 * d), 0, 0);
        info.addView(wxidTv);

        // 第三行: 账号
        TextView aliasTv = new TextView(ctx);
        String aliasStr = (sUserAlias != null && !sUserAlias.isEmpty() && !sUserAlias.equals(sUserWxid))
            ? sUserAlias : (sUserWxid != null ? sUserWxid : "");
        aliasTv.setText("账号: " + aliasStr);
        aliasTv.setTextSize(12);
        aliasTv.setTextColor(AppColors.text2());
        aliasTv.setPadding(0, (int)(3 * d), 0, 0);
        info.addView(aliasTv);

        card.addView(info);
        return card;
    }

    private static View makeMenuItem(Context ctx, float d, String title, int emoji, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(18 * d), (int)(13 * d), (int)(18 * d), (int)(13 * d));
        row.setBackgroundColor(AppColors.whiteCard());
        row.setOnClickListener(onClick);

        // Emoji 图标
        TextView icon = new TextView(ctx);
        icon.setText(new String(Character.toChars(emoji)));
        icon.setTextSize(20);
        icon.setPadding(0, 0, (int)(14 * d), 0);
        row.addView(icon);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tv.setLayoutParams(tvlp);
        row.addView(tv);

        TextView arrow = new TextView(ctx);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(AppColors.text2());
        row.addView(arrow);

        return row;
    }

    static View makeDivider(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(6 * d)));
        v.setBackgroundColor(AppColors.bg());
        return v;
    }

    private static View makeItemDivider(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        lp.setMargins((int)(18 * d), 0, 0, 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(AppColors.divider());
        return v;
    }

    private static View spacerV(Context ctx, int dp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(dp * d)));
        return v;
    }

    static void dismissDialog() {
        if (sActiveDialog != null && sActiveDialog.isShowing()) {
            try { sActiveDialog.dismiss(); } catch (Throwable ignored) {}
        }
        sActiveDialog = null;
    }

    public static void show(Activity act) {
        open(act);
    }
}
