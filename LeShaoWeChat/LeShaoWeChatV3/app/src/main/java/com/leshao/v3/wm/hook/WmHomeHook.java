package com.leshao.v3.wm.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.wm.utils.WmReflect;
import java.util.ArrayList;
import java.util.List;

/**
 * 主页+菜单(5项) — 复刻自微信大师 HomePlusHook
 * 乐少万群定时群发/所有群列表/快捷扫码/朋友圈定时/文件助手
 */
public class WmHomeHook {

    public static void injectMenu(Activity act, ClassLoader cl) {
        if (com.leshao.v3.service.ActivationManager.isCurrentUserBlocked()) return;
        showPanel(act, cl);
    }

    static void showPanel(Activity act, ClassLoader cl) {
        String[] items = {
                "🎮 乐少助手 - 进入模块设置",
                "🚀 乐少万群定时群发 - 勾选群+定时发送",
                "📋 所有群列表 - 查看群+人数",
                "📷 快捷扫码 - 一键启动扫一扫",
                "🕐 朋友圈定时 - 定时发布",
                "📁 文件助手 - 查看微信文件"
        };
        new AlertDialog.Builder(act).setTitle("微信大师")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: openLeShao(act); break;
                        case 1: batchSend(act, cl); break;
                        case 2: allGroups(act, cl); break;
                        case 3: quickScan(act); break;
                        case 4: scheduledMoment(act); break;
                        case 5: fileHelper(act); break;
                    }
                }).show();
    }

    static void openLeShao(Activity act) {
        try {
            act.startActivity(new android.content.Intent()
                    .setClassName("com.leshao.v3", "com.leshao.v3.SettingsActivity"));
        } catch (Exception e) {
            toast(act, "启动乐少助手失败: " + e.getMessage());
        }
    }

    static void batchSend(Activity act, ClassLoader cl) {
        try {
            com.leshao.v3.ui.ContactSelectorView.show(act, false,
                    com.leshao.v3.ui.ContactSelectorView.MODE_GROUP, selected -> {
                        if (selected == null || selected.isEmpty()) { toast(act, "未选择群"); return; }
                        List<String> rooms = new ArrayList<>();
                        for (com.leshao.v3.model.Contact c : selected) rooms.add(c.wxid);
                        showScheduleDialog(act, cl, rooms);
                    });
        } catch (Throwable t) {
            List<String> rooms = WmReflect.getAllChatRooms(cl);
            showScheduleDialog(act, cl, rooms);
        }
    }

    /** 乐少万群定时群发：内容 + 定时时间 → 发送 */
    private static void showScheduleDialog(final Activity act, final ClassLoader cl, final List<String> rooms) {
        final EditText et = new EditText(act);
        et.setHint("消息内容");
        et.setMinLines(2);

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
        ll.addView(et);
        ll.addView(timeLabel);
        ll.addView(timeBtn);

        new AlertDialog.Builder(act).setTitle("乐少万群定时群发 (" + rooms.size() + "个群)")
                .setView(ll).setPositiveButton("发送", (d, w) -> {
                    String msg = et.getText().toString().trim();
                    if (msg.isEmpty()) { toast(act, "消息不能为空"); return; }
                    if (triggerMs[0] > 0 && triggerMs[0] > System.currentTimeMillis()) {
                        long delay = triggerMs[0] - System.currentTimeMillis();
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try { WmReflect.broadcastRooms(cl, rooms, msg); }
                            catch (Throwable t) { LogWriter.log("WmHomeHook", "定时群发失败: " + t.getMessage()); }
                        }, delay);
                        toast(act, "已定时, " + delay / 1000 + " 秒后发送" + rooms.size() + "个群");
                    } else {
                        WmReflect.broadcastRooms(cl, rooms, msg);
                        toast(act, "已发送" + rooms.size() + "个群");
                    }
                }).setNegativeButton("取消", null).show();
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

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    static void allGroups(Activity act, ClassLoader cl) {
        List<String> rooms = WmReflect.getAllChatRooms(cl);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(rooms.size(), 50); i++) {
            String r = rooms.get(i);
            sb.append(r).append("  (").append(WmReflect.getMemberCount(cl, r)).append("人)\n");
        }
        if (rooms.size() > 50) sb.append("...共").append(rooms.size()).append("个群");
        new AlertDialog.Builder(act).setTitle("所有群(" + rooms.size() + ")")
                .setMessage(sb.toString()).setPositiveButton("确定", null).show();
    }

    static void quickScan(Activity act) {
        try {
            act.startActivity(new android.content.Intent().setClassName(
                    "com.tencent.mm", "com.tencent.mm.plugin.scanner.ui.BaseScanUI"));
        } catch (Exception e) { toast(act, "启动失败"); }
    }

    static void scheduledMoment(Activity act) {
        final EditText et = new EditText(act);
        et.setHint("朋友圈内容");
        et.setMinLines(2);
        final EditText dl = new EditText(act);
        dl.setHint("延迟秒数");
        dl.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout ll = new LinearLayout(act);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.addView(et);
        ll.addView(dl);
        new AlertDialog.Builder(act).setTitle("定时朋友圈").setView(ll)
                .setPositiveButton("设置", (d, w) -> {
                    int sec;
                    try { sec = Integer.parseInt(dl.getText().toString().trim()); }
                    catch (Exception e) { sec = 300; }
                    new android.os.Handler().postDelayed(() -> toast(act, "定时朋友圈已触发"), sec * 1000L);
                    toast(act, "已设置" + sec + "秒后");
                }).setNegativeButton("取消", null).show();
    }

    static void fileHelper(Activity act) {
        new AlertDialog.Builder(act).setTitle("文件助手").setMessage(
                "微信文件存储位置:\n\n" +
                        "图片: Pictures/WeChat/\n" +
                        "视频: Movies/WeChat/\n" +
                        "文件: Android/data/com.tencent.mm/\n\n" +
                        "使用系统文件管理器查看").setPositiveButton("确定", null).show();
    }

    static void toast(Activity a, String m) {
        Toast.makeText(a, m, Toast.LENGTH_SHORT).show();
    }
}
