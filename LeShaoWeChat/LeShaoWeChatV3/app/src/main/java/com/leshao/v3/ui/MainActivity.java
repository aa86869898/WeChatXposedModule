package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.db.ContactRepository;
import com.leshao.v3.model.Contact;
import com.leshao.v3.hook.ScheduleBroadcast;
import com.leshao.v3.service.ActivationManager;

import java.io.File;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashMap;
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

    public static String getUserNickname() { return sUserNickname; }
    public static String getUserAlias() { return sUserAlias; }
    public static String getUserWxid() { return sUserWxid; }
    public static String getAvatarPath() { return sAvatarPath; }
    public static String getVipLevel() { return sVipLevel; }

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
        "定时消息助手", "AI智慧助手", "TTS语音播报",
        "红包转账", "朋友圈增强", "隐私安全",
        "数据备份", "娱乐助手", "定时消息群发"
    };
    private static final int[] ITEM_ICONS = {
        0x1F4AC, 0x1F3A8, 0x1F465, 0x1F6E1, 0x1F4E4,
        0x23F0, 0x1F916, 0x1F50A,
        0x1F4B0, 0x1F4F1, 0x1F512,
        0x1F4BE, 0x1F3AE, 0x1F4E3
    };

    private static final java.util.Map<Integer, String> PAGE_FEATURES = new java.util.HashMap<>();
    static {
        PAGE_FEATURES.put(1, "防撤回|自动回复|关键词回复|语音转发|正在输入提示|底部栏增强|聊天UI定制|批量群发|定时发送|自动备注|搜索增强|消息通知|好友检测|删除检测|置顶增强|未读角标|Tab自定义|摇一摇|通话录音|自动接听|输入状态");
        PAGE_FEATURES.put(2, "全局主题|标题栏美化|页面背景|聊天背景|底部Tab美化|自己气泡|对方气泡|文字颜色|Monet引擎|自定义气泡|背景色|文字色|气泡样式|颜色|美化");
        PAGE_FEATURES.put(3, "通讯录导出|联系人变更日志|隐藏敏感字段|群功能增强|群成员日志|群公告回执|批量操作|群管理|踢人|导出成员|通讯录|联系人|入群欢迎|自动踢人|广告检测|禁言|防炸群|匿名发言");
        PAGE_FEATURES.put(4, "群管理|群成员|群公告|批量|踢人|入群欢迎|自动踢人");
        PAGE_FEATURES.put(5, "自动转发|万群|群转发|消息转发");
        PAGE_FEATURES.put(6, "定时消息|定时发送|消息助手|计划消息");
        PAGE_FEATURES.put(7, "AI助手|智慧助手|智能回复|AI");
        PAGE_FEATURES.put(8, "语音播报|TTS播报|排版引擎|配音|API|Voice|间隔|熔断|消息类型|免打扰|安静时段|播报参数|音量|语速|音调|TTS|文字消息播报|语音消息播报|图片消息播报|播报发送人昵称|播报群聊消息|截断长文字");
        PAGE_FEATURES.put(9, "自动抢红包|秒抢|自动收款|红包震动|响铃|红包提醒|转账收款|私聊红包|群聊红包|时间段过滤|延时抢红包|排除群聊|目标群聊|播报金额|关键词过滤");
        PAGE_FEATURES.put(10, "朋友圈|去广告|转发|假点赞|时间修改|视频画质|长视频|增强|长按菜单|复制文字|伪装点赞");
        PAGE_FEATURES.put(11, "隐私保护|截图检测|剪贴板|WebView|指纹锁定|登录监控|会话隐私|隐私|安全|指纹|登录设备监控|设备管理");
        PAGE_FEATURES.put(12, "消息导出|聊天备份|导出聊天|备份数据|查看记录|清除记录|数据备份|导出|自动每日备份|导入外部记录|通讯录变更|变更日志");
        PAGE_FEATURES.put(13, "娱乐|游戏|助手");
        PAGE_FEATURES.put(14, "定时群发|群发消息|批量定时|消息群发|群发助手|任务管理|发送历史|素材管理|文件选取|变量替换");
    }

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
        View titleBar = makeTitleBar(ctx, "乐少多功能助手", false, null);
        root.addView(titleBar);

        // 标题 8 连击重置激活码
        final long[] lastClickTime = {0};
        final int[] clickCount = {0};
        TextView titleTv = (TextView) ((LinearLayout) titleBar).getChildAt(0);
        titleTv.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastClickTime[0] > 3000) { clickCount[0] = 0; }
            lastClickTime[0] = now;
            clickCount[0]++;
            if (clickCount[0] >= 8) {
                clickCount[0] = 0;
                resetActivation(ctx);
                dismissDialog();
                open(act);
                Toast.makeText(ctx, "激活码已重置", Toast.LENGTH_SHORT).show();
            }
        });

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

        // 管理员配置 (仅限管理员可见)
        if (sUserWxid != null && ActivationManager.isAdmin(sUserWxid)) {
            root.addView(makeAdminEntry(ctx, d, act));
            root.addView(makeDivider(ctx));
        }

        // 个人中心按钮
        root.addView(makeProfileBtn(ctx, d, act));

        // 分割线
        root.addView(makeDivider(ctx));

        // 菜单列表容器
        LinearLayout itemsContainer = new LinearLayout(ctx);
        itemsContainer.setOrientation(LinearLayout.VERTICAL);

        // 构建完整列表 + 记录 menuItem → 搜索文本映射（含子功能关键词）
        final java.util.HashMap<View, String> menuSearchTexts = new java.util.HashMap<>();
        boolean first = true;
        for (int i = 0; i < ITEM_NAMES.length; i++) {
            if (i == 1 || i == 5) continue; // 移除主题美化、定时消息助手入口
            if (!first) itemsContainer.addView(makeItemDivider(ctx));
            first = false;
            final int idx = i;
            final int pageId = i + 1;

            View item;
            if (idx == 13) {
                item = makeScheduleEntry(ctx, d, act);
            } else {
                item = makeMenuItem(ctx, d, ITEM_NAMES[i], ITEM_ICONS[i], v -> {
                    dismissDialog();
                    SubPageActivity.openFromMain(act, ITEM_NAMES[idx], pageId);
                });
            }
            item.setTag("menu_item");

            String features = PAGE_FEATURES.get(pageId);
            String searchText = ITEM_NAMES[i] + (features != null ? "|" + features : "");
            menuSearchTexts.put(item, searchText);
            itemsContainer.addView(item);
        }

        root.addView(itemsContainer);
        root.addView(spacerV(ctx, 16));

        // 搜索过滤逻辑 — 同时搜索入口名称和子功能关键词
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int cnt, int aft) {}
            @Override public void onTextChanged(CharSequence s, int st, int bef, int cnt) {}
            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim().toLowerCase();
                boolean anyVisible = false;
                for (java.util.Map.Entry<View, String> entry : menuSearchTexts.entrySet()) {
                    View menuItem = entry.getKey();
                    String searchText = entry.getValue().toLowerCase();
                    if (query.isEmpty() || searchText.contains(query)) {
                        menuItem.setVisibility(View.VISIBLE);
                        anyVisible = true;
                    } else {
                        menuItem.setVisibility(View.GONE);
                    }
                }
                // 同步隐藏/显示分隔线
                for (int i = 0; i < itemsContainer.getChildCount(); i++) {
                    View child = itemsContainer.getChildAt(i);
                    if ("menu_item".equals(child.getTag())) continue;
                    View nextItem = null;
                    for (int j = i + 1; j < itemsContainer.getChildCount(); j++) {
                        if ("menu_item".equals(itemsContainer.getChildAt(j).getTag())) {
                            nextItem = itemsContainer.getChildAt(j);
                            break;
                        }
                    }
                    child.setVisibility(nextItem != null && nextItem.getVisibility() == View.VISIBLE && query.isEmpty() ? View.VISIBLE : View.GONE);
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

    private static View makeAdminEntry(Context ctx, float d, Activity act) {
        LinearLayout entry = new LinearLayout(ctx);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding((int)(18 * d), (int)(13 * d), (int)(18 * d), (int)(13 * d));
        entry.setBackgroundColor(AppColors.whiteCard());
        entry.setOnClickListener(v -> SubPageActivity.openFromMain(act, "管理员配置", 98));

        TextView iconTv = new TextView(ctx);
        iconTv.setText(new String(Character.toChars(0x1F6E1)));
        iconTv.setTextSize(20);
        iconTv.setPadding(0, 0, (int)(14 * d), 0);
        entry.addView(iconTv);

        TextView tv = new TextView(ctx);
        tv.setText("管理员配置");
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        entry.addView(tv);

        TextView arrow = new TextView(ctx);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(AppColors.text2());
        entry.addView(arrow);

        return entry;
    }

    private static View makeProfileBtn(Context ctx, float d, Activity act) {
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding((int)(18 * d), (int)(13 * d), (int)(18 * d), (int)(13 * d));
        btn.setBackgroundColor(AppColors.whiteCard());

        // 图标
        TextView iconTv = new TextView(ctx);
        iconTv.setText(new String(Character.toChars(0x1F464)));
        iconTv.setTextSize(20);
        iconTv.setPadding(0, 0, (int)(14 * d), 0);
        btn.addView(iconTv);

        // 标题
        TextView tv = new TextView(ctx);
        tv.setText("个人中心");
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        btn.addView(tv);

        // 箭头
        TextView arrow = new TextView(ctx);
        arrow.setText(">");
        arrow.setTextSize(16);
        arrow.setTextColor(AppColors.text2());
        btn.addView(arrow);

        btn.setOnClickListener(v -> {
            SubPageActivity.openFromMain(act, "个人中心", 99);
        });

        return btn;
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

    private static View makeScheduleEntry(Context ctx, float d, Activity act) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(18 * d), (int)(13 * d), (int)(12 * d), (int)(13 * d));
        row.setBackgroundColor(AppColors.whiteCard());

        TextView icon = new TextView(ctx);
        icon.setText(new String(Character.toChars(0x1F4E3)));
        icon.setTextSize(20);
        icon.setPadding(0, 0, (int)(14 * d), 0);
        row.addView(icon);

        TextView tv = new TextView(ctx);
        tv.setText("定时消息群发");
        tv.setTextSize(15);
        tv.setTextColor(AppColors.text1());
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tv.setLayoutParams(tvlp);
        row.addView(tv);

        Switch sw = new Switch(ctx);
        sw.setChecked(ScheduleBroadcast.isEnabled());
        updateScheduleSwitchThumb(sw, ScheduleBroadcast.isEnabled());
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ScheduleBroadcast.setEnabled(isChecked);
            updateScheduleSwitchThumb(sw, isChecked);
        });

        row.setOnClickListener(v -> {
            SubPageActivity.openFromMain(act, "定时消息群发", 14);
        });
        row.addView(sw);

        return row;
    }

    private static void updateScheduleSwitchThumb(Switch sw, boolean on) {
        if (on) {
            sw.setThumbResource(android.R.drawable.btn_star_big_on);
        } else {
            sw.setThumbResource(android.R.drawable.btn_default);
        }
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

    private static void resetActivation(Context ctx) {
        SharedPreferences prefs = ContextManager.getPrefs();
        if (prefs == null) return;
        prefs.edit()
            .remove("ls_act_code")
            .remove("ls_act_level")
            .remove("ls_act_expire")
            .remove("ls_act_feature_mask")
            .remove("ls_act_wxid")
            .remove("ls_act_crc")
            .remove("ls_act_time")
            .commit();

        try {
            File backup = new File(ctx.getFilesDir(), "ls_activation.dat");
            if (backup.exists()) backup.delete();
        } catch (Throwable ignored) {}

        LogWriter.log("Main", "激活码已手动重置");
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
