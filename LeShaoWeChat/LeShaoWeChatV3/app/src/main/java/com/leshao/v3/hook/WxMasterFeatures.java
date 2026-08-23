package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * [微信大师移植] 微信大师增强功能 — 生产级完整实现
 * ================================================
 *
 * 移植自"微信大师"项目（com.wp.wm），全部为完整实现：
 *   1. 群信息报告导出   — 群ID/显示名/成员数/群主/创建时间/状态/公告/成员列表
 *   2. 查看群公告弹窗   — 公告内容 + 编辑者 + 发布时间
 *   3. 定时发送消息     — 输入内容+延迟秒数，到期自动发送
 *   4. 群发/广播        — 广播到所有群 / 乐少万群定时群发
 *   5. 私密备注         — 本地按联系人存取备注，不写入微信
 *
 * 双重入口：
 *   A. 聊天页绿色悬浮按钮(⚡) — 自动使用当前聊天对象
 *   B. 模块内"群管理助手"页面  — 手动输入目标 wxid
 */
public class WxMasterFeatures {

    private static final String TAG = "WxMaster";

    private static View sFloatBtn;
    private static WindowManager sWM;
    private static Activity sAct;
    private static ClassLoader sCL;
    private static String sUser;
    private static volatile boolean sEnabled = true;
    private static ClassLoader sWxCl;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        sWxCl = cl;
        if (!sEnabled) return;
        if (!HookConfig.isEnabled("wx_master")) return;
        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);

            XposedBridge.hookAllMethods(chattingUI, "M0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity act = (Activity) XposedHelpers.callMethod(param.thisObject, "getActivity");
                        if (act == null) return;
                        String user = act.getIntent() != null
                                ? act.getIntent().getStringExtra("Chat_User") : null;
                        if (user == null || user.isEmpty()) return;
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> showFloatBtns(act, cl, user), 400);
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(chattingUI, "O0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    dismissFloatBtns();
                }
            });

            LogWriter.log(TAG, "hook installed (M0)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook err: " + t.getMessage());
        }
    }

    // ============================================================
    // 悬浮按钮（⚡功能 + 🛡群管理，群聊显示双按钮）
    // ============================================================

    private static View sGroupBtn;
    private static View sGroupPanel;
    private static boolean sPanelShow;
    private static String sRoom;
    private static final Handler sH = new Handler(Looper.getMainLooper());

    private static void showFloatBtns(Activity act, ClassLoader cl, String user) {
        try {
            if (com.leshao.v3.service.ActivationManager.isCurrentUserBlocked()) {
                LogWriter.log(TAG, "float btns suppressed: user blacklisted");
                return;
            }
            dismissFloatBtns();
            sAct = act;
            sCL = cl;
            sUser = user;
            sWM = (WindowManager) act.getSystemService(act.WINDOW_SERVICE);
            if (sWM == null) return;
            boolean isRoom = user.endsWith("@chatroom") || user.endsWith("@im.chatroom");
            if (isRoom) sRoom = user;

            int sz = dp(40);
            Button btn = new Button(act);
            btn.setText("⚡");
            btn.setTextSize(15);
            btn.setTextColor(Color.WHITE);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(AppColors.accent());
            btn.setBackground(bg);
            WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                    sz, sz,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            wp.gravity = Gravity.TOP | Gravity.RIGHT;
            wp.x = dp(8);
            wp.y = isRoom ? dp(152) : dp(120);
            btn.setOnClickListener(v -> showChatMenu());
            sWM.addView(btn, wp);
            sFloatBtn = btn;

            if (isRoom) {
                Button gbtn = new Button(act);
                gbtn.setText("🛡");
                gbtn.setTextSize(15);
                gbtn.setTextColor(Color.WHITE);
                GradientDrawable gbg = new GradientDrawable();
                gbg.setShape(GradientDrawable.OVAL);
                gbg.setColor(AppColors.accent());
                gbtn.setBackground(gbg);
                WindowManager.LayoutParams gwp = new WindowManager.LayoutParams(
                        sz, sz,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT);
                gwp.gravity = Gravity.TOP | Gravity.RIGHT;
                gwp.x = dp(8);
                gwp.y = dp(108);
                gbtn.setOnClickListener(v -> toggleGroupPanel());
                sWM.addView(gbtn, gwp);
                sGroupBtn = gbtn;
            }
            LogWriter.log(TAG, "float btns shown, room=" + isRoom);
        } catch (Throwable t) {
            LogWriter.log(TAG, "showFloatBtns err: " + t.getMessage());
        }
    }

    private static void dismissFloatBtns() {
        dismissFloatBtn();
        hideGroupPanel();
        if (sGroupBtn != null) {
            try { sWM.removeView(sGroupBtn); } catch (Throwable ignored) {}
            sGroupBtn = null;
        }
    }

    private static void dismissFloatBtn() {
        if (sFloatBtn != null) {
            try { sWM.removeView(sFloatBtn); } catch (Throwable ignored) {}
            sFloatBtn = null;
        }
    }

    private static void showChatMenu() {
        if (sAct == null) return;
        final String[] items;
        if (sRoom != null) {
            items = new String[]{
                "📨 群发/广播",
                "⏰ 定时发送",
                "📌 私密备注",
                "🛡 打开群管理",
            };
        } else {
            items = new String[]{
                "📨 群发/广播",
                "⏰ 定时发送",
                "📌 私密备注",
            };
        }
        new AlertDialog.Builder(sAct)
            .setTitle("⚡ 乐少增强")
            .setItems(items, (d, w) -> {
                switch (w) {
                    case 0: showBroadcastMenu(sAct, sCL); break;
                    case 1: scheduledSendTo(sAct, sCL, sUser); break;
                    case 2: privateNoteFor(sAct, sCL, sUser); break;
                    case 3: showGroupPanel(); break;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void toggleGroupPanel() {
        if (sPanelShow) hideGroupPanel(); else showGroupPanel();
    }

    /** 🛡 群管理悬浮面板 */
    private static void showGroupPanel() {
        if (sAct == null || sRoom == null) return;
        LinearLayout panel = new LinearLayout(sAct);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.WHITE);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.setElevation(16);

        TextView title = new TextView(sAct);
        title.setText("🛡 群管理");
        title.setTextSize(15);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, dp(4));
        panel.addView(title);

        TextView info = new TextView(sAct);
        info.setText("群:" + sRoom);
        info.setTextSize(11);
        info.setTextColor(Color.GRAY);
        info.setPadding(0, 0, 0, dp(8));
        panel.addView(info);

        View div = new View(sAct);
        div.setBackgroundColor(AppColors.offColor());
        div.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        panel.addView(div);

        ScrollView sv = new ScrollView(sAct);
        LinearLayout btns = new LinearLayout(sAct);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.setPadding(0, dp(8), 0, 0);

        addPanelBtn(btns, "📋 群信息报告", () -> exportRoomReportFor(sAct, sCL, sRoom));
        addPanelBtn(btns, "📢 查看群公告", () -> showRoomNoticeFor(sAct, sCL, sRoom));
        addPanelBtn(btns, "📤 广播消息", () -> showBroadcastMenu(sAct, sCL));
        addPanelBtn(btns, "📨 定时发送", () -> scheduledSendTo(sAct, sCL, sRoom));
        addPanelBtn(btns, "📌 私密备注", () -> privateNoteFor(sAct, sCL, sRoom));

        sv.addView(btns);
        panel.addView(sv);

        WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                dp(250), dp(360),
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wp.gravity = Gravity.TOP | Gravity.RIGHT;
        wp.x = dp(4);
        wp.y = dp(100);
        try {
            sWM.addView(panel, wp);
            sGroupPanel = panel;
            sPanelShow = true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "showGroupPanel err: " + t.getMessage());
        }
    }

    private static void hideGroupPanel() {
        if (sGroupPanel != null) {
            try { sWM.removeView(sGroupPanel); } catch (Throwable ignored) {}
            sGroupPanel = null;
        }
        sPanelShow = false;
    }

    private static void addPanelBtn(LinearLayout p, String text, Runnable a) {
        Button btn = new Button(sAct);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        btn.setTextColor(AppColors.text1());
        btn.setBackgroundColor(AppColors.bg());
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(4));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> { hideGroupPanel(); a.run(); });
        p.addView(btn);
    }

    // ============================================================
    // 群发/广播（悬浮按钮入口）
    // ============================================================

    private static void showBroadcastMenu(Activity act, ClassLoader cl) {
        if (act == null) return;
        final String[] items = { "广播到所有群", "乐少万群定时群发" };
        new AlertDialog.Builder(act)
            .setTitle("📨 群发/广播")
            .setItems(items, (d, w) -> {
                if (w == 0) broadcastAll(act, cl);
                else batchSend(act, cl);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 广播到所有群：输入内容 → 发送到全部群聊 */
    public static void broadcastAll(Activity act, ClassLoader cl) {
        if (act == null) return;
        final EditText input = new EditText(act);
        input.setHint("输入要广播的消息内容");
        input.setMinLines(3);

        new AlertDialog.Builder(act)
            .setTitle("广播到所有群")
            .setView(input)
            .setPositiveButton("发送", (d, w) -> {
                String msg = input.getText().toString().trim();
                if (msg.isEmpty()) { toast(act, "消息不能为空"); return; }
                final List<String> rooms = getAllChatRooms(cl);
                if (rooms == null || rooms.isEmpty()) { toast(act, "未获取到群聊列表"); return; }
                boolean ok = sendBroadcast(cl, rooms, msg);
                toast(act, ok ? "已广播到 " + rooms.size() + " 个群" : "发送失败");
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 乐少万群定时群发：模块联系人选择器勾选群 + 内容 + 定时 → 发送 */
    public static void batchSend(Activity act, ClassLoader cl) {
        if (act == null) return;
        try {
            com.leshao.v3.ui.ContactSelectorView.show(act, false,
                    com.leshao.v3.ui.ContactSelectorView.MODE_GROUP, selected -> {
                        if (selected == null || selected.isEmpty()) { toast(act, "未选择群"); return; }
                        List<String> targets = new ArrayList<>();
                        for (com.leshao.v3.model.ContactCard c : selected) targets.add(c.username);
                        showScheduleDialog(act, cl, targets);
                    });
        } catch (Throwable t) {
            List<String> targets = getAllChatRooms(cl);
            showScheduleDialog(act, cl, targets);
        }
    }

    /** 定时群发对话框：内容 + 定时时间 → 发送 */
    private static void showScheduleDialog(final Activity act, final ClassLoader cl, final List<String> targets) {
        final EditText input = new EditText(act);
        input.setHint("输入要群发的消息内容");
        input.setMinLines(2);

        final long[] triggerMs = {0};
        final TextView timeLabel = new TextView(act);
        timeLabel.setText("发送时间: 立即发送");
        timeLabel.setTextSize(13);
        timeLabel.setTextColor(AppColors.text2());
        timeLabel.setPadding(0, dp(act, 8), 0, 0);

        Button timeBtn = new Button(act);
        timeBtn.setText("选择定时时间");
        timeBtn.setTextSize(13);
        timeBtn.setAllCaps(false);
        timeBtn.setTextColor(AppColors.WHITE_TEXT);
        android.graphics.drawable.GradientDrawable tbBg = new android.graphics.drawable.GradientDrawable();
        tbBg.setColor(AppColors.accent());
        tbBg.setCornerRadius(dp(act, 8));
        timeBtn.setBackground(tbBg);
        timeBtn.setOnClickListener(v -> showDateTimePicker(act, triggerMs, timeLabel));

        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(act, 20), 0, dp(act, 20), 0);
        ll.addView(input);
        ll.addView(timeLabel);
        ll.addView(timeBtn);

        new AlertDialog.Builder(act)
            .setTitle("乐少万群定时群发 (" + targets.size() + "个群)")
            .setView(ll)
            .setPositiveButton("发送", (d, w) -> {
                String msg = input.getText().toString().trim();
                if (msg.isEmpty()) { toast(act, "消息不能为空"); return; }
                if (triggerMs[0] > 0 && triggerMs[0] > System.currentTimeMillis()) {
                    long delay = triggerMs[0] - System.currentTimeMillis();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        try { sendBroadcast(cl, targets, msg); }
                        catch (Throwable t) { LogWriter.log(TAG, "定时群发失败: " + t.getMessage()); }
                    }, delay);
                    toast(act, "已定时, " + delay / 1000 + " 秒后发送到 " + targets.size() + " 个群");
                } else {
                    boolean ok = sendBroadcast(cl, targets, msg);
                    toast(act, ok ? "已发送到 " + targets.size() + " 个群" : "发送失败");
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static void showDateTimePicker(final Activity act, final long[] result, final TextView label) {
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(act, (view, year, month, dayOfMonth) -> {
            final int y = year, mo = month, d = dayOfMonth;
            new android.app.TimePickerDialog(act, (tv, hour, minute) -> {
                cal.set(y, mo, d, hour, minute, 0);
                result[0] = cal.getTimeInMillis();
                label.setText("发送时间: " + new java.text.SimpleDateFormat("MM-dd HH:mm")
                        .format(new java.util.Date(result[0])));
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show();
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    /** 群发核心：先尝试微信原生群发接口(qj 逗号分隔)，失败回退逐群发送 */
    private static boolean sendBroadcast(ClassLoader cl, List<String> rooms, String content) {
        if (sWxCl != null) cl = sWxCl;
        if (rooms == null || rooms.isEmpty() || content == null) return false;
        if (sendViaQj(cl, rooms, content)) return true;
        int ok = 0;
        for (String room : rooms) {
            try {
                GroupFeatures.sendTextMessage(cl, room, content);
                ok++;
            } catch (Throwable ignored) {}
        }
        return ok > 0;
    }

    /** 微信原生群发接口：SendMsgMgr.qj(content, toUsersCsv) */
    private static boolean sendViaQj(ClassLoader cl, List<String> rooms, String content) {
        if (sWxCl != null) cl = sWxCl;
        try {
            Class<?> n0 = XposedHelpers.findClass("pa5.n0", cl);
            Class<?> s5 = XposedHelpers.findClass("kl5.s5", cl);
            Object mgr = XposedHelpers.callStaticMethod(n0, "c", s5);
            if (mgr == null) return false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rooms.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(rooms.get(i));
            }
            XposedHelpers.callMethod(mgr, "qj", content, sb.toString());
            LogWriter.log(TAG, "sendViaQj ok, rooms=" + rooms.size());
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendViaQj fail: " + t.getMessage());
            return false;
        }
    }

    // ============================================================
    // 定时发送
    // ============================================================

    /** 定时发送对话框：发送到指定目标（悬浮按钮传入当前聊天对象，页面传入用户输入） */
    public static void scheduledSendTo(Activity act, ClassLoader cl, String target) {
        if (act == null) return;
        final ClassLoader wxCl = sWxCl != null ? sWxCl : cl;
        final String finalTarget = target == null ? "" : target;
        final EditText et = new EditText(act);
        et.setHint("消息内容");
        et.setMinLines(2);
        final EditText dl = new EditText(act);
        dl.setHint("延迟秒数(60=1分钟)");
        dl.setInputType(2);
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(act, 20), 0, dp(act, 20), 0);
        ll.addView(et);
        ll.addView(dl);

        new AlertDialog.Builder(act)
            .setTitle("定时发送 → " + finalTarget)
            .setView(ll)
            .setPositiveButton("开始", (d, w) -> {
                String msg = et.getText().toString().trim();
                if (msg.isEmpty()) { toast(act, "消息不能为空"); return; }
                int sec;
                try { sec = Integer.parseInt(dl.getText().toString().trim()); }
                catch (Exception e) { sec = 60; }
                if (sec <= 0) sec = 60;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (finalTarget != null && !finalTarget.isEmpty()) {
                        GroupFeatures.sendTextMessage(wxCl, finalTarget, msg);
                        LogWriter.log(TAG, "scheduled msg sent to " + finalTarget);
                    }
                }, sec * 1000L);
                toast(act, "已设置 " + sec + " 秒后发送");
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 定时发送（模块页面入口：需要用户输入目标 wxid） */
    public static void scheduledSend(Activity act, ClassLoader cl) {
        if (act == null) return;
        final EditText targetEt = new EditText(act);
        targetEt.setHint("目标 wxid（如 xxx@chatroom 或 wxid_xxx）");
        targetEt.setSingleLine(true);
        new AlertDialog.Builder(act)
            .setTitle("定时发送")
            .setView(targetEt)
            .setPositiveButton("下一步", (d, w) -> {
                String t = targetEt.getText().toString().trim();
                if (t.isEmpty()) { toast(act, "请输入目标 wxid"); return; }
                scheduledSendTo(act, cl, t);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ============================================================
    // 私密备注（本地存储，不写入微信）
    // ============================================================

    public static void privateNoteFor(Activity act, ClassLoader cl, String user) {
        if (act == null || user == null || user.isEmpty()) return;
        final String key = "ls_private_note_" + user;
        final EditText et = new EditText(act);
        et.setHint("仅本机可见的备注");
        String old = readPrivateNote(key);
        et.setText(old == null ? "" : old);
        et.setMinLines(2);

        new AlertDialog.Builder(act)
            .setTitle("私密备注 (" + user + ")")
            .setView(et)
            .setPositiveButton("保存", (d, w) -> {
                savePrivateNote(key, et.getText().toString().trim());
                toast(act, "已保存");
            })
            .setNegativeButton("清除", (d, w) -> {
                savePrivateNote(key, "");
                toast(act, "已清除");
            })
            .setNeutralButton("取消", null)
            .show();
    }

    /** 私密备注（模块页面入口：需要用户输入目标 wxid） */
    public static void privateNote(Activity act, ClassLoader cl) {
        if (act == null) return;
        final EditText targetEt = new EditText(act);
        targetEt.setHint("目标 wxid（如 wxid_xxx）");
        targetEt.setSingleLine(true);
        new AlertDialog.Builder(act)
            .setTitle("私密备注")
            .setView(targetEt)
            .setPositiveButton("下一步", (d, w) -> {
                String t = targetEt.getText().toString().trim();
                if (t.isEmpty()) { toast(act, "请输入目标 wxid"); return; }
                privateNoteFor(act, cl, t);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static String readPrivateNote(String key) {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            return sp == null ? null : sp.getString(key, null);
        } catch (Throwable t) { return null; }
    }

    private static void savePrivateNote(String key, String value) {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sp.edit().putString(key, value).apply();
        } catch (Throwable ignored) {}
    }

    // ============================================================
    // 群信息报告 / 查看群公告（GroupCardSelectUI 菜单调用，自动从 intent 取群ID）
    // ============================================================

    public static void exportRoomReport(Object activity) {
        Activity act = (Activity) activity;
        String room = chatroomFromIntent(act);
        if (room == null || room.isEmpty()) { toast(act, "无法获取群ID"); return; }
        exportRoomReportFor(act, act.getClassLoader(), room);
    }

    public static void showRoomNotice(Object activity) {
        Activity act = (Activity) activity;
        String room = chatroomFromIntent(act);
        if (room == null || room.isEmpty()) { toast(act, "无法获取群ID"); return; }
        showRoomNoticeFor(act, act.getClassLoader(), room);
    }

    /** 群信息报告（模块页面入口：手动指定群 wxid） */
    public static void exportRoomReportFor(Activity act, ClassLoader cl, String room) {
        try {
            if (sWxCl != null) cl = sWxCl;
            if (act == null || room == null || room.isEmpty()) { toast(act, "请输入群wxid"); return; }
            Object info = getChatroomInfo(cl, room);

            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════\n");
            sb.append("       群信息完整报告\n");
            sb.append("═══════════════════════════════════\n\n");

            sb.append("【基本信息】\n");
            sb.append("群聊ID: ").append(room).append("\n");
            sb.append("显示名: ").append(strField(info, "field_displayname", "未知")).append("\n");
            sb.append("成员数: ").append(intField(info, "field_memberCount")).append("\n");
            sb.append("群主: ").append(strField(info, "field_roomowner", "未知")).append("\n");
            long createTime = longField(info, "field_addtime");
            sb.append("创建时间: ").append(createTime > 0 ? fmtTime(createTime) : "未知").append("\n");
            int status = intField(info, "field_chatroomStatus");
            sb.append("群状态: ").append(status == 0 ? "正常" : "异常/已解散").append("\n");
            sb.append("垃圾消息状态: ").append(intField(info, "field_spamStatus")).append("\n");
            sb.append("二维码访问: ").append(intField(info, "field_qrCodeAccessType")).append("\n");

            sb.append("\n【我的信息】\n");
            sb.append("我的群昵称: ").append(strField(info, "field_selfDisplayName", "未知")).append("\n");

            sb.append("\n【群公告】\n");
            String notice = strField(info, "field_chatroomnotice", "");
            if (notice != null && !notice.isEmpty()) {
                sb.append(notice).append("\n");
                sb.append("编辑者: ").append(strField(info, "field_chatroomnoticeEditor", "未知")).append("\n");
                long pubTime = longField(info, "field_chatroomnoticePublishTime");
                if (pubTime > 0) sb.append("发布时间: ").append(fmtTime(pubTime)).append("\n");
            } else {
                sb.append("(无公告)\n");
            }

            sb.append("\n【成员列表】\n");
            List<String> members = getMemberList(cl, room);
            int shown = 0;
            for (String m : members) {
                sb.append("  ").append(m).append("\n");
                shown++;
                if (shown >= 100) { sb.append("  ... 共").append(members.size()).append("人\n"); break; }
            }
            if (members.isEmpty()) sb.append("(未获取到成员列表)\n");

            File dir = new File("/sdcard/LeShaoV3Logs/");
            if (!dir.exists()) dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File file = new File(dir, "report_" + room + "_" + ts + ".txt");
            FileOutputStream fos = new FileOutputStream(file);
            try { fos.write(sb.toString().getBytes("UTF-8")); } finally { fos.close(); }
            LogWriter.log(TAG, "room report exported: " + file.getAbsolutePath());
            toast(act, "已导出: " + file.getAbsolutePath());
        } catch (Throwable t) {
            toast(act, "导出失败: " + t.getMessage());
            LogWriter.log(TAG, "exportRoomReport err: " + t.getMessage());
        }
    }

    /** 查看群公告（模块页面入口：手动指定群 wxid） */
    public static void showRoomNoticeFor(Activity act, ClassLoader cl, String room) {
        try {
            if (sWxCl != null) cl = sWxCl;
            if (act == null || room == null || room.isEmpty()) { toast(act, "请输入群wxid"); return; }
            Object info = getChatroomInfo(cl, room);

            String notice = strField(info, "field_chatroomnotice", "");
            String editor = strField(info, "field_chatroomnoticeEditor", "");
            long pubTime = longField(info, "field_chatroomnoticePublishTime");

            StringBuilder sb = new StringBuilder();
            if (notice == null || notice.isEmpty()) {
                sb.append("(暂无群公告)");
            } else {
                sb.append(notice);
                if (editor != null && !editor.isEmpty()) {
                    sb.append("\n\n— ").append(editor);
                }
                if (pubTime > 0) {
                    sb.append("\n").append(fmtTime(pubTime));
                }
            }
            new AlertDialog.Builder(act)
                .setTitle("群公告")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
        } catch (Throwable t) {
            toast(act, "获取公告失败");
            LogWriter.log(TAG, "showRoomNotice err: " + t.getMessage());
        }
    }

    /** 群信息报告（模块页面入口：先输入群 wxid） */
    public static void exportRoomReportPicker(Activity act, ClassLoader cl) {
        if (act == null) return;
        final EditText et = new EditText(act);
        et.setHint("群 wxid（如 xxx@chatroom）");
        et.setSingleLine(true);
        new AlertDialog.Builder(act)
            .setTitle("群信息报告")
            .setView(et)
            .setPositiveButton("导出", (d, w) -> {
                String room = et.getText().toString().trim();
                if (room.isEmpty()) { toast(act, "请输入群wxid"); return; }
                exportRoomReportFor(act, cl, room);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 查看群公告（模块页面入口：先输入群 wxid） */
    public static void showRoomNoticePicker(Activity act, ClassLoader cl) {
        if (act == null) return;
        final EditText et = new EditText(act);
        et.setHint("群 wxid（如 xxx@chatroom）");
        et.setSingleLine(true);
        new AlertDialog.Builder(act)
            .setTitle("查看群公告")
            .setView(et)
            .setPositiveButton("查看", (d, w) -> {
                String room = et.getText().toString().trim();
                if (room.isEmpty()) { toast(act, "请输入群wxid"); return; }
                showRoomNoticeFor(act, cl, room);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static String chatroomFromIntent(Activity act) {
        if (act == null || act.getIntent() == null) return null;
        String v = act.getIntent().getStringExtra("Chatroom_Name");
        if (v == null || v.isEmpty()) v = act.getIntent().getStringExtra("Chat_User");
        return v;
    }

    // ============================================================
    // 导出全部群列表
    // ============================================================

    /** 导出全部群列表为 CSV（模块页面入口） */
    public static void exportAllRooms(Activity act, ClassLoader cl) {
        if (sWxCl != null) cl = sWxCl;
        if (act == null) return;
        try {
            List<String> rooms = getAllChatRooms(cl);
            StringBuilder sb = new StringBuilder();
            sb.append("群ID,成员数,群主,显示名\n");
            for (String r : rooms) {
                Object info = getChatroomInfo(cl, r);
                sb.append(r).append(",");
                sb.append(intField(info, "field_memberCount")).append(",");
                sb.append(strField(info, "field_roomowner", "")).append(",");
                sb.append(strField(info, "field_displayname", "")).append("\n");
            }
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "WeChatMaster");
            dir.mkdirs();
            File f = new File(dir, "allrooms_" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write(sb.toString().getBytes("UTF-8")); } finally { fos.close(); }
            toast(act, "已导出全部群:" + f.getName() + " (" + rooms.size() + "个)");
        } catch (Throwable t) {
            toast(act, "导出失败:" + t.getMessage());
        }
    }

    // ============================================================
    // 微信反射工具（移植自微信大师，全部 try/catch 降级）
    // ============================================================

    /** 获取群信息对象 dm.y1：hm0.j1.s(f).a().H0(room) */
    private static Object getChatroomInfo(ClassLoader cl, String room) {
        if (sWxCl != null) cl = sWxCl;
        try {
            Class<?> j1 = XposedHelpers.findClass("hm0.j1", cl);
            Class<?> fCls = XposedHelpers.findClass("cw1.f", cl);
            Object svc = XposedHelpers.callStaticMethod(j1, "s", fCls);
            if (svc == null) return null;
            Object inst = XposedHelpers.callMethod(svc, "a");
            if (inst == null) return null;
            return XposedHelpers.callMethod(inst, "H0", room);
        } catch (Throwable t) {
            LogWriter.log(TAG, "getChatroomInfo err: " + t.getMessage());
            return null;
        }
    }

    /** 获取群成员列表：e01.v1.m(room) */
    @SuppressWarnings("unchecked")
    private static List<String> getMemberList(ClassLoader cl, String room) {
        if (sWxCl != null) cl = sWxCl;
        try {
            Class<?> v1 = XposedHelpers.findClass("e01.v1", cl);
            Object r = XposedHelpers.callStaticMethod(v1, "m", room);
            if (r instanceof List) return (List<String>) r;
            return new ArrayList<>();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** 获取所有群聊列表：e01.d9.b().q() → storage.D() cursor */
    private static List<String> getAllChatRooms(ClassLoader cl) {
        if (sWxCl != null) cl = sWxCl;
        List<String> rooms = new ArrayList<>();
        Cursor c = null;
        try {
            Class<?> d9 = XposedHelpers.findClass("e01.d9", cl);
            Object b = XposedHelpers.callStaticMethod(d9, "b");
            if (b == null) return rooms;
            Object storage = XposedHelpers.callMethod(b, "q");
            if (storage == null) return rooms;
            c = (Cursor) XposedHelpers.callMethod(storage, "D");
            if (c == null) return rooms;
            while (c.moveToNext()) {
                int idx = c.getColumnIndex("username");
                if (idx < 0) break;
                String u = c.getString(idx);
                if (u != null && (u.endsWith("@chatroom") || u.endsWith("@im.chatroom"))) {
                    rooms.add(u);
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "getAllChatRooms err: " + t.getMessage());
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        return rooms;
    }

    /** 获取群成员数 */
    private static int getMemberCount(ClassLoader cl, String room) {
        Object info = getChatroomInfo(cl, room);
        return intField(info, "field_memberCount");
    }

    private static String strField(Object info, String field, String def) {
        try {
            if (info == null) return def;
            Object v = XposedHelpers.getObjectField(info, field);
            return v == null ? def : String.valueOf(v);
        } catch (Throwable t) { return def; }
    }

    private static long longField(Object info, String field) {
        try {
            if (info == null) return 0;
            return XposedHelpers.getLongField(info, field);
        } catch (Throwable t) { return 0; }
    }

    private static int intField(Object info, String field) {
        try {
            if (info == null) return 0;
            return XposedHelpers.getIntField(info, field);
        } catch (Throwable t) { return 0; }
    }

    private static String fmtTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
    }

    // ============================================================
    // 工具
    // ============================================================

    private static int dp(int d) {
        return sAct == null ? d : (int) (d * sAct.getResources().getDisplayMetrics().density);
    }

    private static int dp(Activity act, int d) {
        return act == null ? d : (int) (d * act.getResources().getDisplayMetrics().density);
    }

    private static void toast(Activity act, String msg) {
        if (act != null) Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
