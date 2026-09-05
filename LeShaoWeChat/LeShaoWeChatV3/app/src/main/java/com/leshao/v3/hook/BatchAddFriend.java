package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.AvatarHelper;
import com.leshao.v3.ui.BatchAddRecordPageView;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.SubPageActivity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 群聊详情页批量加好友。
 *
 *   - ChatroomInfoUI.onCreate 注入「批量加友」文本菜单
 *   - 配置弹窗（排除项 + 随机延迟 + 来源选择 + 添加记录入口）
 *   - 「选择成员」按钮单独弹出成员多选弹窗（头像 + 昵称 + 搜索 + 全选）
 *   - 确定后遍历勾选成员，逐个构造 NetSceneVerifyUser(m3) 投递 net scene queue，
 *     每添加一个好友之间随机延迟；每条结果写入 BatchAddRecordStore。
 *
 * 模块内只提供总开关 ls_batch_add_enabled，其余配置都在本类弹窗内完成。
 */
public final class BatchAddFriend {

    private static final String TAG = "BatchAddFriend";

    // ================= 符号表（复刻 SymbolTable DEFAULT，8.0.76 已核实） =================
    private static final String KERNEL = "hm0.j1";
    private static final String SERVICE_METHOD = "s";
    private static final String NET_SERVICE_METHOD = "n";
    private static final String NET_QUEUE_FIELD = "b";
    private static final String MEMBERS_LOGIC = "e01.v1";
    private static final String CHATROOM_MGR_IF = "cw1.f";
    private static final String SELF = "e01.z1";
    private static final String CONTACT_MGR_IF = "sh3.c4";
    private static final String NETSCENE_VERIFY = "com.tencent.mm.pluginsdk.model.m3";

    private static final String CHATROOM_INFO_UI = "com.tencent.mm.chatroom.ui.ChatroomInfoUI";
    private static final String MM_ACTIVITY = "com.tencent.mm.ui.MMActivity";
    private static final String EXTRA_ROOM_ID = "RoomInfo_Id";
    private static final int MENU_ID_BATCH_ADD = 0x5E5E;
    // 群聊批量加好友固定使用「群聊」来源 scene
    private static final int SCENE_GROUP_CHAT = 12;

    // ================= 运行时状态 =================
    private static ClassLoader sCL;
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static volatile boolean sEnabled = false;

    private BatchAddFriend() {}

    public static void setEnabled(boolean v) { sEnabled = v; }
    public static boolean isEnabled() { return sEnabled; }

    private static Class<?> cls(String name) {
        return XposedHelpers.findClass(name, sCL);
    }

    public static void hook(ClassLoader cl) {
        sCL = cl;
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sEnabled = sp.getBoolean("ls_batch_add_enabled", false);
        } catch (Throwable t) {
            LogWriter.log(TAG, "读配置失败: " + t.getMessage());
        }
        LogWriter.log(TAG, "hook() 开始, enabled=" + sEnabled);

        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                String clsName = p.thisObject.getClass().getName();
                                if (!CHATROOM_INFO_UI.equals(clsName)) return;
                                LogWriter.log(TAG, "ChatroomInfoUI.onCreate triggered, enabled=" + sEnabled);
                                if (!sEnabled) return;
                                injectButton((Activity) p.thisObject);
                            } catch (Throwable e) {
                                LogWriter.log("BatchAddFriend", "cb err: " + e);
                            }
                        }
                    });
            LogWriter.log(TAG, "hook onCreate OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook onCreate fail: " + t);
        }

        // 给「批量加友」菜单文字加下划线（菜单创建/刷新时机统一处理）
        try {
            XC_MethodHook underlineHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                                        if (!sEnabled) return;
                                        if (p.args.length > 0 && p.args[0] instanceof Menu) {
                                            applyMenuUnderline((Menu) p.args[0]);
                                        }
                    } catch (Throwable e) {
                        LogWriter.log("BatchAddFriend", "cb err: " + e);
                    }
                }
            };
            XposedBridge.hookAllMethods(cls(MM_ACTIVITY), "onCreateOptionsMenu", underlineHook);
            XposedBridge.hookAllMethods(cls(MM_ACTIVITY), "onPrepareOptionsMenu", underlineHook);
            LogWriter.log(TAG, "hook 菜单下划线 OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook 菜单下划线 fail: " + t);
        }
    }

    private static void applyMenuUnderline(Menu menu) {
        if (menu == null) return;
        try {
            MenuItem item = menu.findItem(MENU_ID_BATCH_ADD);
            if (item == null) return;
            SpannableString ss = new SpannableString("批量加友");
            ss.setSpan(new UnderlineSpan(), 0, ss.length(), 0);
            item.setTitle(ss);
        } catch (Throwable ignored) {}
    }

    // ---------- ① 注入按钮 ----------
private static void injectButton(final Activity act) {
        sMain.post(() -> {
            try {
                Method addText = findAddTextOptionMenu(act);
                if (addText == null) {
                    LogWriter.log(TAG, "injectButton: 未找到 addTextOptionMenu(4参)");
                    return;
                }
                addText.setAccessible(true);
                addText.invoke(act, MENU_ID_BATCH_ADD, "批量加友", 0,
                        (MenuItem.OnMenuItemClickListener) item -> {
                            try {
                                showConfigDialog(act);
                                return true;
                            } catch (Throwable e) {
                                LogWriter.log("BatchAddFriend", "runner err: " + e);
                                return false;
                            }
                        });
                LogWriter.log(TAG, "injectButton OK");
            } catch (Throwable t) {
                LogWriter.log(TAG, "injectButton fail: " + t);
            }
        });
    }

    private static Method findAddTextOptionMenu(Activity act) {
        try {
            Class<?> actCls = act.getClass();
            while (actCls != null && actCls != Object.class) {
                try {
                    for (Method m : actCls.getDeclaredMethods()) {
                        if (m.getName().equals("addTextOptionMenu") && m.getParameterTypes().length == 4) {
                            LogWriter.log(TAG, "findAddTextOptionMenu: " + actCls.getName());
                            return m;
                        }
                    }
                } catch (Throwable ignored) {}
                actCls = actCls.getSuperclass();
            }
            LogWriter.log(TAG, "injectButton: 未找到 addTextOptionMenu(4参)");
            return null;
        } catch (Throwable t) {
            LogWriter.log(TAG, "findAddTextOptionMenu err: " + t);
            return null;
        }
    }

    // ---------- ② 成员模型 ----------
    private static class Member {
        String username;
        String displayName;
        boolean selected = false;
    }

    // ---------- ③ 配置弹窗（Step 1） ----------
    private static void showConfigDialog(final Activity act) {
        sMain.post(() -> {
            try {
                String roomName = act.getIntent().getStringExtra(EXTRA_ROOM_ID);
                if (roomName == null || roomName.isEmpty()) {
                    roomName = act.getIntent().getStringExtra("Chatroom_Name");
                }
                if (roomName == null || roomName.isEmpty()) {
                    LogWriter.log(TAG, "未获取到群ID");
                    toast(act, "未获取到群ID");
                    return;
                }
                buildConfigDialog(act, roomName);
            } catch (Throwable t) {
                LogWriter.log(TAG, "showConfigDialog fail: " + t);
            }
        });
    }

    private static void buildConfigDialog(final Activity act, final String roomName) {
        final float d = act.getResources().getDisplayMetrics().density;
        final SharedPreferences sp = ContextManager.getPrefs();

        boolean exOwner = sp != null && sp.getBoolean("ls_batch_add_exclude_owner", true);
        boolean exAdmin = sp != null && sp.getBoolean("ls_batch_add_exclude_admin", true);
        boolean exSelf = sp != null && sp.getBoolean("ls_batch_add_exclude_self", true);
        boolean exFriend = sp != null && sp.getBoolean("ls_batch_add_exclude_friend", true);
        int delayMin = sp != null ? sp.getInt("ls_batch_add_delay_min", 2) : 2;
        int delayMax = sp != null ? sp.getInt("ls_batch_add_delay_max", 5) : 5;
        String greeting = sp != null ? sp.getString("ls_batch_add_greeting", "") : "";

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);

        // 标题
        TextView title = new TextView(act);
        title.setText("批量加好友");
        title.setTextSize(16);
        title.setTextColor(AppColors.TEXT_TITLE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 10));
        root.addView(title);

        // 成员选择入口行
        final List<Member> members = new ArrayList<>();
        final TextView countTv = new TextView(act);
        countTv.setTextSize(13);
        countTv.setTextColor(AppColors.TEXT_NOTE);
        countTv.setText("尚未选择群成员");

        LinearLayout rowPick = new LinearLayout(act);
        rowPick.setOrientation(LinearLayout.HORIZONTAL);
        rowPick.setGravity(Gravity.CENTER_VERTICAL);
        rowPick.setPadding(dp(act, 16), dp(act, 6), dp(act, 16), dp(act, 6));

        LinearLayout pickTextCol = new LinearLayout(act);
        pickTextCol.setOrientation(LinearLayout.VERTICAL);
        pickTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView tvPick = new TextView(act);
        tvPick.setText("选择群成员");
        tvPick.setTextSize(15);
        tvPick.setTextColor(AppColors.TEXT_TITLE);
        tvPick.setTypeface(null, Typeface.BOLD);
        pickTextCol.addView(tvPick);
        countTv.setPadding(0, dp(act, 3), 0, 0);
        pickTextCol.addView(countTv);
        rowPick.addView(pickTextCol);

        TextView btnPick = pillBtn(act, d, "选择成员", AppColors.ACCENT, AppColors.WHITE_TEXT);
        rowPick.addView(btnPick);
        root.addView(rowPick);

        btnPick.setOnClickListener(v -> showMemberPicker(act, roomName, members, countTv));

        root.addView(divider(act, d));

        // 排除项（圆形勾选，模块风格）
        final boolean[] stOwner = {exOwner};
        final boolean[] stAdmin = {exAdmin};
        final boolean[] stSelf = {exSelf};
        final boolean[] stFriend = {exFriend};
        root.addView(buildCheckRow(act, d, "排除群主", stOwner));
        root.addView(divider(act, d));
        root.addView(buildCheckRow(act, d, "排除管理员", stAdmin));
        root.addView(divider(act, d));
        root.addView(buildCheckRow(act, d, "排除自己", stSelf));
        root.addView(divider(act, d));
        root.addView(buildCheckRow(act, d, "排除已是好友", stFriend));
        root.addView(divider(act, d));

        // 随机延迟
        LinearLayout rowDelay = new LinearLayout(act);
        rowDelay.setOrientation(LinearLayout.HORIZONTAL);
        rowDelay.setGravity(Gravity.CENTER_VERTICAL);
        rowDelay.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 2));
        TextView tvDelay = new TextView(act);
        tvDelay.setText("随机延迟");
        tvDelay.setTextSize(13);
        tvDelay.setTextColor(AppColors.TEXT_BODY);
        tvDelay.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        rowDelay.addView(tvDelay);
        final EditText etMin = numInput(act, d, String.valueOf(delayMin));
        TextView tvTilde = new TextView(act);
        tvTilde.setText("~");
        tvTilde.setTextColor(AppColors.TEXT_NOTE);
        tvTilde.setPadding(dp(act, 8), 0, dp(act, 8), 0);
        final EditText etMax = numInput(act, d, String.valueOf(delayMax));
        TextView tvSec = new TextView(act);
        tvSec.setText("秒");
        tvSec.setTextSize(13);
        tvSec.setTextColor(AppColors.TEXT_BODY);
        tvSec.setPadding(dp(act, 8), 0, 0, 0);
        rowDelay.addView(etMin, new LinearLayout.LayoutParams(dp(act, 56), -2));
        rowDelay.addView(tvTilde);
        rowDelay.addView(etMax, new LinearLayout.LayoutParams(dp(act, 56), -2));
        rowDelay.addView(tvSec);
        root.addView(rowDelay);
        root.addView(divider(act, d));

        // 打招呼语（选填，留空则不发送验证语）
        LinearLayout rowGreeting = new LinearLayout(act);
        rowGreeting.setOrientation(LinearLayout.HORIZONTAL);
        rowGreeting.setGravity(Gravity.CENTER_VERTICAL);
        rowGreeting.setPadding(dp(act, 16), dp(act, 2), dp(act, 16), dp(act, 2));
        TextView tvGreeting = new TextView(act);
        tvGreeting.setText("打招呼语");
        tvGreeting.setTextSize(13);
        tvGreeting.setTextColor(AppColors.TEXT_BODY);
        tvGreeting.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        rowGreeting.addView(tvGreeting);
        final EditText etGreeting = textInput(act, d, greeting);
        rowGreeting.addView(etGreeting, new LinearLayout.LayoutParams(0, -2, 1.3f));
        root.addView(rowGreeting);
        root.addView(divider(act, d));

        // 添加记录入口
        LinearLayout rowRecord = new LinearLayout(act);
        rowRecord.setOrientation(LinearLayout.HORIZONTAL);
        rowRecord.setGravity(Gravity.CENTER_VERTICAL);
        rowRecord.setPadding(dp(act, 16), dp(act, 2), dp(act, 16), dp(act, 8));
        TextView tvRecord = new TextView(act);
        tvRecord.setText("添加记录");
        tvRecord.setTextSize(13);
        tvRecord.setTextColor(AppColors.TEXT_BODY);
        tvRecord.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        rowRecord.addView(tvRecord);
        TextView btnRecord = new TextView(act);
        btnRecord.setText("查看记录");
        btnRecord.setTextSize(13);
        btnRecord.setTextColor(AppColors.ACCENT);
        btnRecord.setPadding(dp(act, 10), 0, 0, 0);
        btnRecord.setPaintFlags(btnRecord.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        btnRecord.setOnClickListener(v -> {
            try {
                SubPageActivity.openStandalone(act, "添加记录", 15);
            } catch (Throwable t) {
                LogWriter.log(TAG, "打开添加记录失败: " + t.getMessage());
            }
        });
        rowRecord.addView(btnRecord);
        root.addView(rowRecord);
        root.addView(divider(act, d));

        // 底部按钮
        LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 16));

        TextView cancel = pillBtn(act, d, "取消", AppColors.DIVIDER, AppColors.TEXT_BODY);
        TextView start = pillBtn(act, d, "开始添加", AppColors.ACCENT, AppColors.WHITE_TEXT);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.leftMargin = dp(act, 8);
        bLp.rightMargin = dp(act, 8);
        bottom.addView(cancel, bLp);
        bottom.addView(start, bLp);
        root.addView(bottom);

        AlertDialog dialog = new AlertDialog.Builder(act, dialogTheme())
                .setView(root)
                .setCancelable(true)
                .create();

        cancel.setOnClickListener(v -> dialog.dismiss());

        start.setOnClickListener(v -> {
            try {
                        List<String> selected = new ArrayList<>();
                        for (Member m : members) {
                            if (m.selected) selected.add(m.username);
                        }
                        if (selected.isEmpty()) {
                            Toast.makeText(act, "请先点击[选择成员]勾选要添加的人", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        boolean o = stOwner[0];
                        boolean a = stAdmin[0];
                        boolean sl = stSelf[0];
                        boolean f = stFriend[0];
                        int dm = parseInt(etMin.getText().toString().trim());
                        int dx = parseInt(etMax.getText().toString().trim());
                        if (dx < dm) dx = dm;
                        String gv = etGreeting.getText().toString().trim();
                        if (sp != null) {
                            sp.edit()
                                    .putBoolean("ls_batch_add_exclude_owner", o)
                                    .putBoolean("ls_batch_add_exclude_admin", a)
                                    .putBoolean("ls_batch_add_exclude_self", sl)
                                    .putBoolean("ls_batch_add_exclude_friend", f)
                                    .putInt("ls_batch_add_delay_min", dm)
                                    .putInt("ls_batch_add_delay_max", dx)
                                    .putString("ls_batch_add_greeting", gv)
                                    .apply();
                        }
                        LogWriter.log(TAG, "配置: 勾选=" + selected.size() + "人"
                                + " excludeOwner=" + o + " excludeAdmin=" + a + " excludeSelf=" + sl + " excludeFriend=" + f
                                + " delay=" + dm + "~" + dx + "s greeting=" + gv);
                        dialog.dismiss();
                        doBatchAdd(act, roomName, selected, o, a, sl, f, dm, dx, gv);
            } catch (Throwable e) {
                LogWriter.log("BatchAddFriend", "runner err: " + e);
            }
        });

        dialog.show();
        styleDialog(dialog, act);
    }

    // ---------- ④ 成员选择弹窗（Step 2） ----------
    private static void showMemberPicker(final Activity act, final String roomName,
                                         final List<Member> members, final TextView countTv) {
        if (!members.isEmpty()) {
            buildMemberDialog(act, members, countTv);
            return;
        }
        Toast.makeText(act, "正在加载群成员...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                @SuppressWarnings("unchecked")
                List<String> rawMembers = (List<String>) XposedHelpers
                        .callStaticMethod(cls(MEMBERS_LOGIC), "m", roomName);
                String self = (String) XposedHelpers.callStaticMethod(cls(SELF), "r");

                Object storage = null;
                try { storage = getContactStorage(); } catch (Throwable ignored) {}

                List<Member> loaded = new ArrayList<>();
                if (rawMembers != null) {
                    for (String u : rawMembers) {
                        if (u == null || u.equals(self)) continue;   // 自己默认不显示在列表
                        Member m = new Member();
                        m.username = u;
                        m.displayName = resolveDisplayName(storage, u);
                        loaded.add(m);
                    }
                }

                if (loaded.isEmpty()) {
                    sMain.post(() -> toast(act, "未获取到群成员"));
                    return;
                }

                LogWriter.log(TAG, "加载群成员完成: room=" + roomName + " count=" + loaded.size());
                sMain.post(() -> {
                    try {
                                        members.clear();
                                        members.addAll(loaded);
                                        buildMemberDialog(act, members, countTv);
                    } catch (Throwable e) {
                        LogWriter.log("BatchAddFriend", "runner err: " + e);
                    }
                });
            } catch (Throwable t) {
                LogWriter.log(TAG, "showMemberPicker fail: " + t);
                sMain.post(() -> toast(act, "加载群成员失败，见日志"));
            }
        }).start();
    }

    private static String resolveDisplayName(Object storage, String u) {
        String remark = null, nick = null;
        try {
            Object contact = XposedHelpers.callMethod(storage, "n", u, true);
            if (contact != null) {
                try {
                    remark = (String) XposedHelpers.getObjectField(contact, "field_conRemark");
                } catch (Throwable ignored) {}
                try {
                    nick = (String) XposedHelpers.getObjectField(contact, "field_nickname");
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        if (remark != null && !remark.isEmpty()) return remark;
        if (nick != null && !nick.isEmpty()) return nick;
        ContactCard card = ContactRepository.findByUsername(u);
        if (card != null && card.displayName() != null && !card.displayName().isEmpty()) {
            return card.displayName();
        }
        return u;
    }

    private static void buildMemberDialog(final Activity act, final List<Member> members,
                                          final TextView countTv) {
        final float d = act.getResources().getDisplayMetrics().density;
        final int avatarSize = dp(act, 40);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);

        // 标题
        final TextView title = new TextView(act);
        title.setTextSize(16);
        title.setTextColor(AppColors.TEXT_TITLE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 10));
        root.addView(title);

        // 搜索框（粗边框）
        final EditText search = new EditText(act);
        search.setHint("搜索群成员");
        search.setHintTextColor(AppColors.TEXT_NOTE);
        search.setTextSize(14);
        search.setTextColor(AppColors.TEXT_TITLE);
        search.setSingleLine(true);
        search.setPadding(dp(act, 14), dp(act, 8), dp(act, 14), dp(act, 8));
        search.setBackground(roundBg(act, d, AppColors.INPUT_BG, dp(act, 20), dp(act, 1f), AppColors.DIVIDER));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(act, 16), 0, dp(act, 16), dp(act, 8));
        root.addView(search, slp);

        // 成员列表
        final LinearLayout listRoot = new LinearLayout(act);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(dp(act, 12), 0, dp(act, 12), 0);
        final ScrollView sv = new ScrollView(act);
        sv.addView(listRoot);
        final int maxListH = dp(act, 440);
        final LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(sv, svLp);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            try {
                        listRoot.removeAllViews();
                        String f = search.getText().toString().toLowerCase().trim();
                        int visible = 0, total = 0;
                        for (Member m : members) {
                            if (!f.isEmpty() && !m.displayName.toLowerCase().contains(f)
                                    && !m.username.toLowerCase().contains(f)) continue;
                            listRoot.addView(buildMemberRow(act, d, m, avatarSize, refresh[0]));
                            visible++;
                        }
                        for (Member m : members) if (m.selected) total++;
                        title.setText("选择群成员 (已选 " + total + "/" + members.size() + ")");

                        int rows = Math.max(visible, 1);
                        svLp.height = Math.min(rows * dp(act, 52), maxListH);
                        sv.setLayoutParams(svLp);
            } catch (Throwable e) {
                LogWriter.log("BatchAddFriend", "runner err: " + e);
            }
        };

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh[0].run(); }
        });

        refresh[0].run();

        // 底部按钮
        LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(act, 12), dp(act, 8), dp(act, 12), dp(act, 16));

        TextView cancel = pillBtn(act, d, "取消", AppColors.DIVIDER, AppColors.TEXT_BODY);
        TextView toggle = pillBtn(act, d, "全选", AppColors.CANDY_PINK, AppColors.WHITE_TEXT);
        TextView confirm = pillBtn(act, d, "确定", AppColors.ACCENT, AppColors.WHITE_TEXT);

        final boolean[] allOn = {false};
        toggle.setOnClickListener(v -> {
            try {
                        allOn[0] = !allOn[0];
                        String f = search.getText().toString().toLowerCase().trim();
                        for (Member m : members) {
                            if (!f.isEmpty() && !m.displayName.toLowerCase().contains(f)
                                    && !m.username.toLowerCase().contains(f)) continue;
                            m.selected = allOn[0];
                        }
                        toggle.setText(allOn[0] ? "取消全选" : "全选");
                        refresh[0].run();
            } catch (Throwable e) {
                LogWriter.log("BatchAddFriend", "runner err: " + e);
            }
        });

        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.leftMargin = dp(act, 8);
        bLp.rightMargin = dp(act, 8);
        bottom.addView(cancel, bLp);
        bottom.addView(toggle, bLp);
        bottom.addView(confirm, bLp);
        root.addView(bottom);

        AlertDialog dialog = new AlertDialog.Builder(act, dialogTheme())
                .setView(root)
                .setCancelable(true)
                .create();

        cancel.setOnClickListener(v -> dialog.dismiss());

        confirm.setOnClickListener(v -> {
            try {
                        int n = 0;
                        for (Member m : members) if (m.selected) n++;
                        countTv.setText(n > 0 ? ("已选 " + n + " 人") : "尚未选择群成员");
                        dialog.dismiss();
            } catch (Throwable e) {
                LogWriter.log("BatchAddFriend", "runner err: " + e);
            }
        });

        dialog.show();
        styleDialog(dialog, act);
    }

    private static View buildMemberRow(final Activity act, final float d, final Member m,
                                       final int avatarSize, final Runnable refresh) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));

        // 勾选
        ImageView cb = new ImageView(act);
        cb.setImageDrawable(makeCheckbox(act, d, m.selected));
        cb.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(dp(act, 28), dp(act, 28));
        cblp.setMargins(0, 0, dp(act, 8), 0);
        row.addView(cb, cblp);

        // 头像（字母兜底 + 异步加载真实头像）
        FrameLayout avatar = new FrameLayout(act);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        alp.setMargins(0, 0, dp(act, 12), 0);
        avatar.setLayoutParams(alp);

        TextView initial = new TextView(act);
        initial.setGravity(Gravity.CENTER);
        initial.setTextSize(16);
        initial.setTextColor(Color.WHITE);
        initial.setText(firstChar(m.displayName));
        initial.setBackground(makeAvatarBg(m.username));
        avatar.addView(initial, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        row.addView(avatar);

        loadAvatarInto(act, iv, m.username, avatarSize);

        // 名称
        LinearLayout textCol = new LinearLayout(act);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(act);
        name.setText(m.displayName);
        name.setTextSize(14);
        name.setTextColor(AppColors.TEXT_TITLE);
        textCol.addView(name);

        if (!m.username.equals(m.displayName)) {
            TextView wxid = new TextView(act);
            wxid.setText(m.username);
            wxid.setTextSize(11);
            wxid.setTextColor(AppColors.TEXT_NOTE);
            wxid.setPadding(0, dp(act, 2), 0, 0);
            textCol.addView(wxid);
        }

        row.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        row.setOnClickListener(v -> {
            try {
                        m.selected = !m.selected;
                        cb.setImageDrawable(makeCheckbox(act, d, m.selected));
                        refresh.run();
            } catch (Throwable e) {
                LogWriter.log("BatchAddFriend", "runner err: " + e);
            }
        });
        return row;
    }

    private static void loadAvatarInto(final Activity act, final ImageView iv,
                                       final String username, final int size) {
        // 磁盘优先（已缓存头像立即显示）；磁盘未命中再走微信官方 API 异步绑定
        new Thread(() -> {
            try {
                Bitmap bm = AvatarHelper.loadAvatar(username, size);
                if (bm != null && !bm.isRecycled()) {
                    final Bitmap scaled = Bitmap.createScaledBitmap(bm, size, size, true);
                    iv.post(() -> iv.setImageBitmap(scaled));
                    return;
                }
            } catch (Throwable ignored) {}
                try {
                } catch (Throwable e) {
                    LogWriter.log("BatchAddFriend", "runner err: " + e);
                }
            iv.post(() -> AvatarHelper.bindAvatar(iv, username));
        }, "leshao-batch-avatar").start();
    }

    private static GradientDrawable makeAvatarBg(String u) {
        int[] candy = AppColors.isDarkMode()
                ? new int[]{0xFF7A4A63, 0xFF5B3A8A, 0xFF2E6E8E, 0xFF8A6E3A, 0xFF2E6E5B, 0xFF4A4A8A}
                : new int[]{0xFFFF6B8A, 0xFFA855F7, 0xFF38BDF8, 0xFFF59E0B, 0xFF10B981, 0xFF6366F1};
        int idx = Math.abs(u == null ? 0 : u.hashCode()) % candy.length;
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(candy[idx]);
        gd.setShape(GradientDrawable.OVAL);
        return gd;
    }

    // ---------- ⑤ 核心业务 ----------
     private static void doBatchAdd(final Activity act, final String roomName,
                                    final List<String> selected,
                                    final boolean exOwner, final boolean exAdmin,
                                    final boolean exSelf, final boolean exFriend,
                                    final int minDelaySec, final int maxDelaySec,
                                    final String greeting) {
         new Thread(() -> {
             try {
                 LogWriter.log(TAG, "doBatchAdd: room=" + roomName
                         + " selected=" + selected.size() + " exOwner=" + exOwner
                         + " exAdmin=" + exAdmin + " exSelf=" + exSelf + " exFriend=" + exFriend
                         + " scene=" + SCENE_GROUP_CHAT + " delay=" + minDelaySec + "~" + maxDelaySec + "s"
                         + " greeting=" + greeting);

                String owner = getRoomOwner(roomName);
                String self = (String) XposedHelpers.callStaticMethod(cls(SELF), "r");
                Object storage = getContactStorage();
                Object queue = getNetSceneQueue();

                Random rnd = new Random();
                int done = 0;
                for (String u : selected) {
                    if (u == null) continue;
                    if (exSelf && u.equals(self)) {
                        record(u, "", false, "已排除(自己)");
                        continue;
                    }
                    if (exOwner && u.equals(owner)) {
                        record(u, "", false, "已排除(群主)");
                        continue;
                    }

                    Object contact = XposedHelpers.callMethod(storage, "n", u, true);
                    if (contact == null) {
                        record(u, "", false, "联系人信息缺失");
                        continue;
                    }
                    int type = (Integer) XposedHelpers.callMethod(contact, "getType");
                    if (exFriend && (type & 0x1) != 0) {
                        record(u, "", false, "已排除(已是好友)");
                        continue;
                    }
                    if (exAdmin && isAdmin(contact)) {
                        record(u, "", false, "已排除(管理员)");
                        continue;
                    }

                    try {
                        Object sc = XposedHelpers.newInstance(
                                cls(NETSCENE_VERIFY), 3, u, greeting, SCENE_GROUP_CHAT);
                        XposedHelpers.callMethod(queue, "g", sc);
                        done++;
                        LogWriter.log(TAG, "已发送好友请求: " + u + " (" + done + ")");
                        record(u, "", true, "已发送好友请求");
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "发送失败: " + u + " " + e.getMessage());
                        record(u, "", false, "发送失败: " + e.getMessage());
                    }

                    int delayMs = 0;
                    if (maxDelaySec > 0) {
                        int span = Math.max(0, maxDelaySec - minDelaySec);
                        delayMs = (minDelaySec + (span > 0 ? rnd.nextInt(span + 1) : 0)) * 1000;
                    }
                    if (delayMs > 0) {
                        LogWriter.log(TAG, "随机延迟 " + delayMs + "ms");
                        Thread.sleep(delayMs);
                    }
                }

                final int n = done;
                sMain.post(() -> Toast.makeText(act, "已发起 " + n + " 个好友请求",
                        Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                try {
                } catch (Throwable e) {
                    LogWriter.log("BatchAddFriend", "runner err: " + e);
                }
                LogWriter.log(TAG, "doBatchAdd fail: " + t);
                toast(act, "批量加好友失败，见日志");
            }
        }).start();
    }

    private static void record(String username, String displayName, boolean success, String reason) {
        try {
            if (displayName == null || displayName.isEmpty()) {
                ContactCard card = ContactRepository.findByUsername(username);
                if (card != null && card.displayName() != null && !card.displayName().isEmpty()) {
                    displayName = card.displayName();
                }
            }
            BatchAddRecordStore.add(username, displayName, success, reason);
        } catch (Throwable ignored) {}
    }

    // ---------- ⑥ 工具 ----------
    private static Object svc(String iface) {
        return XposedHelpers.callStaticMethod(cls(KERNEL), SERVICE_METHOD, cls(iface));
    }

    private static Object getChatroomInfo(String roomName) {
        Object mgr = svc(CHATROOM_MGR_IF);
        Object storage = XposedHelpers.callMethod(mgr, "a");
        return XposedHelpers.callMethod(storage, "H0", roomName);
    }

    private static String getRoomOwner(String roomName) {
        Object room = getChatroomInfo(roomName);
        if (room == null) return null;
        return (String) XposedHelpers.getObjectField(room, "field_roomowner");
    }

    private static Object getContactStorage() {
        Object mgr = svc(CONTACT_MGR_IF);
        return XposedHelpers.callMethod(mgr, "ij");
    }

    private static boolean isAdmin(Object contact) {
        if (contact == null) return false;
        try {
            int flag = XposedHelpers.getIntField(contact, "field_chatroomFlag");
            return (flag & 2) != 0;
        } catch (Throwable t) {
            try {
                return XposedHelpers.getIntField(contact, "T") == 0;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    private static Object getNetSceneQueue() {
        Object net = XposedHelpers.callStaticMethod(cls(KERNEL), NET_SERVICE_METHOD);
        return XposedHelpers.getObjectField(net, NET_QUEUE_FIELD);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s == null ? "0" : s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void toast(final Activity act, final String msg) {
        sMain.post(() -> Toast.makeText(act, msg, Toast.LENGTH_SHORT).show());
    }

    // ---------- 模块风格 UI 辅助 ----------
    private static int dp(Activity act, float px) {
        return (int) (px * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static View buildCheckRow(final Activity act, final float d, final String label,
                                      final boolean[] state) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 16), dp(act, 6), dp(act, 16), dp(act, 2));

        ImageView cb = new ImageView(act);
        cb.setImageDrawable(makeCheckbox(act, d, state[0]));
        cb.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(dp(act, 26), dp(act, 26));
        cblp.setMargins(0, 0, dp(act, 10), 0);
        row.addView(cb, cblp);

        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.TEXT_BODY);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(tv);

        row.setOnClickListener(v -> {
            try {
                        state[0] = !state[0];
                        cb.setImageDrawable(makeCheckbox(act, d, state[0]));
            } catch (Throwable e) {
                LogWriter.log("BatchAddFriend", "runner err: " + e);
            }
        });
        return row;
    }

    private static int dialogTheme() {
        return AppColors.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar;
    }

    private static void styleDialog(AlertDialog dialog, Activity act) {
        try {
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(CandyUi.dialogBg(act));
                w.setLayout(WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {}
    }

    private static EditText numInput(Activity act, float d, String value) {
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(value);
        et.setTextSize(13);
        et.setTextColor(AppColors.TEXT_TITLE);
        et.setGravity(Gravity.CENTER);
        et.setPadding(dp(act, 6), dp(act, 4), dp(act, 6), dp(act, 4));
        et.setBackground(roundBg(act, d, AppColors.INPUT_BG, dp(act, 12), dp(act, 1f), AppColors.DIVIDER));
        return et;
    }

    private static EditText textInput(Activity act, float d, String value) {
        EditText et = new EditText(act);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        et.setHint("选填，留空则不发送验证语");
        et.setText(value);
        et.setTextSize(13);
        et.setTextColor(AppColors.TEXT_TITLE);
        et.setHintTextColor(AppColors.TEXT_NOTE);
        et.setPadding(dp(act, 10), dp(act, 6), dp(act, 10), dp(act, 6));
        et.setBackground(roundBg(act, d, AppColors.INPUT_BG, dp(act, 12), dp(act, 1f), AppColors.DIVIDER));
        return et;
    }

    private static TextView pillBtn(Activity act, float d, String text, int bg, int fg) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(act, 22), dp(act, 8), dp(act, 22), dp(act, 8));
        tv.setBackground(roundBg(act, d, bg, dp(act, 20), 0, 0));
        return tv;
    }

    private static View divider(Activity act, float d) {
        View v = new View(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 6));
        v.setLayoutParams(lp);
        v.setBackgroundColor(AppColors.DIVIDER);
        return v;
    }

    private static GradientDrawable roundBg(Activity act, float d, int color, int radius,
                                            float strokeDp, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        if (strokeDp > 0) {
            gd.setStroke((int) (strokeDp * act.getResources().getDisplayMetrics().density + 0.5f), strokeColor);
        }
        return gd;
    }

    private static Drawable makeCheckbox(Activity act, float d, boolean checked) {
        int size = dp(act, 22);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        if (checked) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(AppColors.ACCENT);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, fill);
            Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
            check.setColor(Color.WHITE);
            check.setStrokeWidth(dp(act, 2.2f));
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawLine(size * 0.32f, size * 0.52f, size * 0.46f, size * 0.66f, check);
            canvas.drawLine(size * 0.46f, size * 0.66f, size * 0.72f, size * 0.35f, check);
        } else {
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(AppColors.DIVIDER);
            stroke.setStrokeWidth(dp(act, 2));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, stroke);
        }
        return new BitmapDrawable(act.getResources(), bm);
    }

    private static String firstChar(String s) {
        if (s == null || s.isEmpty()) return "?";
        return s.substring(0, 1);
    }
}
