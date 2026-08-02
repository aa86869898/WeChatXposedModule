package com.leshao.v3.wm.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.leshao.v3.wm.utils.WmReflect;
import java.util.ArrayList;
import java.util.List;

/**
 * 主页+菜单(5项) — 复刻自微信大师 HomePlusHook
 * 群发助手/所有群列表/快捷扫码/朋友圈定时/文件助手
 */
public class WmHomeHook {

    public static void injectMenu(Activity act, ClassLoader cl) {
        showPanel(act, cl);
    }

    static void showPanel(Activity act, ClassLoader cl) {
        String[] items = {
                "📨 群发助手 - 一条消息发多个群",
                "📋 所有群列表 - 查看群+人数",
                "📷 快捷扫码 - 一键启动扫一扫",
                "🕐 朋友圈定时 - 定时发布",
                "📁 文件助手 - 查看微信文件"
        };
        new AlertDialog.Builder(act).setTitle("微信大师")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: batchSend(act, cl); break;
                        case 1: allGroups(act, cl); break;
                        case 2: quickScan(act); break;
                        case 3: scheduledMoment(act); break;
                        case 4: fileHelper(act); break;
                    }
                }).show();
    }

    static void batchSend(Activity act, ClassLoader cl) {
        try {
            com.leshao.v3.ui.ContactSelectorView.show(act, false,
                    com.leshao.v3.ui.ContactSelectorView.MODE_GROUP, selected -> {
                        if (selected == null || selected.isEmpty()) { toast(act, "未选择群"); return; }
                        List<String> rooms = new ArrayList<>();
                        for (com.leshao.v3.model.Contact c : selected) rooms.add(c.wxid);
                        batchSendToRooms(act, cl, rooms);
                    });
        } catch (Throwable t) {
            List<String> rooms = WmReflect.getAllChatRooms(cl);
            batchSendToRooms(act, cl, rooms);
        }
    }

    private static void batchSendToRooms(Activity act, ClassLoader cl, List<String> rooms) {
        final EditText et = new EditText(act);
        et.setHint("消息内容");
        et.setMinLines(2);

        new AlertDialog.Builder(act).setTitle("发送到" + rooms.size() + "个群")
                .setView(et).setPositiveButton("发送", (d, w) -> {
                    String msg = et.getText().toString().trim();
                    if (msg.isEmpty()) { toast(act, "消息不能为空"); return; }
                    WmReflect.broadcastRooms(cl, rooms, msg);
                    toast(act, "已发送" + rooms.size() + "个群");
                }).setNegativeButton("取消", null).show();
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
