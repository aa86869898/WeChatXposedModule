package com.leshao.v3.wm.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.wm.utils.WmPrefs;
import com.leshao.v3.wm.utils.WmReflect;
import com.leshao.v3.wm.utils.WmUi;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 群管理功能注入 — 复刻自微信大师 GroupFeatureHook
 * 标题栏右上角🛡按钮 → 群管理面板
 * 仅对群聊显示。修复原版 showKick 截断/exportAllRooms 缺失。
 */
public class WmGroupHook {
    private static com.leshao.v3.wm.utils.WmUi.DragFloat sGrpFloat;
    private static Dialog sGrpDialog;
    private static WindowManager sWM;
    private static String sRoom;
    private static ClassLoader sCL;
    private static Activity sAct;
    private static boolean sPanelOn;

    private static final String TAG = "WmGroup";

    /** 供聊天页⚡面板复用群管理功能时绑定上下文 */
    static void bind(Activity act, ClassLoader cl, String room) {
        if (act != null) sAct = act;
        if (cl != null) sCL = cl;
        if (room != null) sRoom = room;
    }

    /** 群详情页直接打开面板（不依赖浮动按钮） */
    public static void showGroupPanel(Activity act, ClassLoader cl, String room) {
        sAct = act;
        sCL = cl;
        sRoom = room;
        sWM = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
        if (room == null || !WmReflect.isChatRoom(cl, room)) return;
        if (sPanelOn) { hidePanel(); return; }
        showPanel();
    }

    public static void dismissGroupBtn() {
        hidePanel();
        if (sGrpFloat != null) {
            sGrpFloat.removeFromWindow();
            sGrpFloat = null;
        }
        sAct = null;
    }

    static void showPanel() {
        if (sAct == null || sAct.isFinishing()) return;
        LinearLayout panel = com.leshao.v3.wm.utils.WmUi.makePanel(sAct);
        GradientDrawable bsBg = new GradientDrawable();
        bsBg.setColor(com.leshao.v3.ui.AppColors.card());
        bsBg.setCornerRadius(dp(18));
        panel.setBackground(bsBg);

        ScrollView sv = new ScrollView(sAct);
        LinearLayout btns = new LinearLayout(sAct);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.setPadding(0, 0, 0, dp(0));

        btns.addView(com.leshao.v3.wm.utils.WmUi.makeHeader(sAct, "🛡 乐少群管理",
                makeRoomSubtitle()));

        appendGroupButtons(btns);

        sv.addView(btns);
        panel.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        panel.addView(com.leshao.v3.wm.utils.WmUi.makePrimaryBtn(sAct, "✕ 收起面板",
                WmGroupHook::hidePanel));

        Dialog dialog = new Dialog(sAct);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> { sPanelOn = false; });

        dialog.show();
        applyPanelWindow(dialog);
        sGrpDialog = dialog;
        sPanelOn = true;
    }

    private static void applyPanelWindow(Dialog dialog) {
        android.view.Window w = dialog.getWindow();
        if (w == null) return;
        int pw = dp(250);
        int ph = dp(560);
        w.setLayout(pw, ph);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0.05f;

        if (sGrpFloat != null) {
            int[] loc = new int[2];
            try { sGrpFloat.btn.getLocationOnScreen(loc); } catch (Exception ignored) {}
            int fx = loc[0] + sGrpFloat.btn.getWidth() / 2 - pw / 2;
            int fy = loc[1] - ph - dp(8);
            int screenW = sAct.getResources().getDisplayMetrics().widthPixels;
            int screenH = sAct.getResources().getDisplayMetrics().heightPixels;
            if (fx < 0) fx = dp(5);
            if (fx + pw > screenW) fx = screenW - pw - dp(5);
            if (fy < 0) fy = dp(5);
            if (fy + ph > screenH) fy = screenH - ph - dp(5);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            lp.x = fx;
            lp.y = fy;
        } else {
            lp.gravity = android.view.Gravity.CENTER;
        }
        w.setAttributes(lp);
    }

    static void hidePanel() {
        if (sGrpDialog != null) {
            try { sGrpDialog.dismiss(); } catch (Exception ignored) {}
            sGrpDialog = null;
        }
        sPanelOn = false;
    }

    /** 群信息摘要 */
    static String makeRoomSubtitle() {
        int cnt = WmReflect.getMemberCount(sCL, sRoom);
        String display = WmReflect.getRoomDisplayName(sCL, sRoom);
        if (display == null || display.isEmpty()) display = sRoom;
        return "群聊名称 " + display + "\n成员人数 " + cnt + "人";
    }

    /** 群管理按钮列表，供自身面板与聊天页⚡面板共用 */
    static void appendGroupButtons(LinearLayout btns) {
        if (WmPrefs.isGrpExport()) btns.addView(WmUi.makeBtn(sAct, "📋 导出成员列表", WmGroupHook::exportMembers));
        if (WmPrefs.isGrpWxid()) btns.addView(WmUi.makeBtn(sAct, "👀 查看成员wxid", WmGroupHook::showMemberWxid));
        if (WmPrefs.isGrpReport()) btns.addView(WmUi.makeBtn(sAct, "📋 群信息报告", WmGroupHook::exportReport));
        if (WmPrefs.isGrpNotice()) btns.addView(WmUi.makeBtn(sAct, "📢 查看群公告", WmGroupHook::showNotice));
        if (WmPrefs.isGrpBroadcast()) btns.addView(WmUi.makeBtn(sAct, "📤 广播消息", WmGroupHook::showBroadcast));
        if (WmPrefs.isGrpInvite()) btns.addView(WmUi.makeBtn(sAct, "➕ 邀请好友进群", WmGroupHook::showInvite));
        if (WmPrefs.isGrpKick()) btns.addView(WmUi.makeBtn(sAct, "🚫 踢出成员", WmGroupHook::showKick));
        if (WmPrefs.isGrpQuit()) btns.addView(WmUi.makeBtn(sAct, "🚪 退出群聊", WmGroupHook::quitGroup));
        if (WmPrefs.isGrpAll()) btns.addView(WmUi.makeBtn(sAct, "📊 导出全部群", WmGroupHook::exportAllRooms));
    }

    // ==== 功能实现 ====

    static void exportMembers() {
        try {
            List<String> ms = WmReflect.getMemberList(sCL, sRoom);
            // 兜底数据源已清空（原 ContactRepository 群信息），待重写
            if (ms == null || ms.isEmpty()) { toast("未获取到成员列表"); return; }

            StringBuilder sb = new StringBuilder();
            sb.append("wxid,nickname,remark,alias\n");
            for (String u : ms) {
                Object c = WmReflect.getContact(sCL, u);
                sb.append(u).append(",");
                sb.append(csv(c != null ? WmReflect.getNickname(c) : "")).append(",");
                sb.append(csv(c != null ? WmReflect.getRemark(c) : "")).append(",");
                sb.append(csv(c != null ? WmReflect.getAlias(c) : "")).append("\n");
            }
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "WeChatMaster");
            dir.mkdirs();
            File f = new File(dir, "members_" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write(sb.toString().getBytes("UTF-8")); } finally { fos.close(); }
            toast("已导出:" + f.getName() + " (" + ms.size() + "人)");
        } catch (Exception e) {
            LogWriter.log(TAG, "exportMembers err: " + e.getMessage());
            toast("导出失败: " + e.getMessage());
        }
    }

    static void showMemberWxid() {
        try {
            final List<String> ms = WmReflect.getMemberList(sCL, sRoom);
            if (ms == null || ms.isEmpty()) {
                toast("未获取到成员列表");
                return;
            }
            int cnt = ms.size();
            ListView lv = new ListView(sAct);
            lv.setAdapter(new MemberAdapter(ms));
            lv.setFastScrollEnabled(true);
            AlertDialog dlg = new AlertDialog.Builder(sAct)
                    .setTitle("群成员 (" + cnt + "人)")
                    .setView(lv)
                    .setPositiveButton("关闭", null)
                    .create();
            dlg.show();
        } catch (Exception e) {
            LogWriter.log(TAG, "showMemberWxid err: " + e.getMessage());
            toast("加载失败: " + e.getMessage());
        }
    }

    static class MemberAdapter extends BaseAdapter {
        final List<String> mList;
        MemberAdapter(List<String> list) { mList = list; }
        @Override public int getCount() { return mList.size(); }
        @Override public Object getItem(int p) { return mList.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View row, ViewGroup parent) {
            String wxid = mList.get(p);
            Object c = WmReflect.getContact(sCL, wxid);
            String n = WmReflect.getRemark(c);
            if (n == null || n.isEmpty()) n = WmReflect.getNickname(c);
            if (n == null || n.isEmpty()) n = wxid;
            if (row == null) {
                LinearLayout l = new LinearLayout(sAct);
                l.setOrientation(LinearLayout.HORIZONTAL);
                l.setPadding(dp(8), dp(4), dp(8), dp(4));
                ImageView iv = new ImageView(sAct);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(dp(44), dp(44)));
                l.addView(iv);
                iv.setTag(android.R.id.icon); // reuse as tag pattern
                TextView tv = new TextView(sAct);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setPadding(dp(8), 0, 0, 0);
                tv.setTextColor(AppColors.text1());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, dp(44), 1f);
                l.addView(tv, lp);
                row = l;
            }
            ImageView iv = (ImageView) ((ViewGroup) row).getChildAt(0);
            iv.setImageBitmap(null); // clear previous
            WmReflect.loadAvatarInto(sCL, iv, wxid);
            TextView tv = (TextView) ((ViewGroup) row).getChildAt(1);
            tv.setText((p + 1) + ". " + n);
            return row;
        }
    }

    static void exportReport() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("════ 群信息报告 ════\n\n");
            sb.append("群聊ID: ").append(sRoom).append("\n");
            try { sb.append("显示名: ").append(nvl(WmReflect.getRoomDisplayName(sCL, sRoom), "未知")).append("\n"); } catch (Exception ignored) {}
            try { sb.append("成员数: ").append(WmReflect.getMemberCount(sCL, sRoom)).append("\n"); } catch (Exception ignored) {}
            try { sb.append("群主: ").append(nvl(WmReflect.getRoomOwner(sCL, sRoom), "未知")).append("\n"); } catch (Exception ignored) {}
            try {
                long ct = WmReflect.getRoomCreateTime(sCL, sRoom);
                sb.append("创建: ").append(ct > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ct)) : "未知").append("\n");
            } catch (Exception ignored) {}
            try { sb.append("状态: ").append(WmReflect.isRoomDisbanded(sCL, sRoom) ? "已解散" : "正常").append("\n"); } catch (Exception ignored) {}
            sb.append("\n【群公告】\n");
            try {
                String n = WmReflect.getRoomNotice(sCL, sRoom);
                sb.append(n != null && !n.isEmpty() ? n : "(无)");
            } catch (Exception ignored) { sb.append("(获取失败)"); }
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "WeChatMaster");
            dir.mkdirs();
            File f = new File(dir, "report_" + System.currentTimeMillis() + ".txt");
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write(sb.toString().getBytes("UTF-8")); } finally { fos.close(); }
            toast("已导出:" + f.getName());
        } catch (Exception e) {
            LogWriter.log(TAG, "exportReport err: " + e.getMessage());
            toast("导出失败: " + e.getMessage());
        }
    }

    static String nvl(String v, String def) { return v != null && !v.isEmpty() ? v : def; }

    static void showNotice() {
        String n = WmReflect.getRoomNotice(sCL, sRoom);
        String ed = WmReflect.getRoomNoticeEditor(sCL, sRoom);
        long pt = WmReflect.getRoomNoticePubTime(sCL, sRoom);
        StringBuilder sb = new StringBuilder();
        sb.append(n != null && !n.isEmpty() ? n : "(暂无群公告)");
        if (ed != null && !ed.isEmpty()) sb.append("\n\n— ").append(ed);
        if (pt > 0) sb.append("\n").append(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(pt)));
        new AlertDialog.Builder(sAct).setTitle("群公告").setMessage(sb.toString())
                .setPositiveButton("关闭", null).show();
    }

    static void showBroadcast() {
        try {
            com.leshao.v3.ui.ContactSelectorView.show(sAct, false,
                    com.leshao.v3.ui.ContactSelectorView.MODE_GROUP, selected -> {
                        if (selected == null || selected.isEmpty()) { toast("未选择群"); return; }
                        showBroadcastInput(sAct, selected);
                    });
        } catch (Throwable t) {
            final EditText et = new EditText(sAct);
            et.setHint("消息内容");
            et.setMinLines(2);
            new AlertDialog.Builder(sAct).setTitle("广播到所有群").setView(et)
                    .setPositiveButton("发送", (d, w) -> {
                        String msg = et.getText().toString().trim();
                        if (msg.isEmpty()) { toast("消息不能为空"); return; }
                        List<String> rooms = WmReflect.getAllChatRooms(sCL);
                        WmReflect.broadcastRooms(sCL, rooms, msg);
                        toast("已广播" + rooms.size() + "个群");
                    }).setNegativeButton("取消", null).show();
        }
    }

    // 已选好目标群 → 输入消息 → 广播
    static void showBroadcastInput(Activity act, List<com.leshao.v3.model.ContactCard> groups) {
        final EditText et = new EditText(act);
        et.setHint("消息内容");
        et.setMinLines(2);
        new AlertDialog.Builder(act).setTitle("广播到" + groups.size() + "个群").setView(et)
                .setPositiveButton("发送", (d, w) -> {
                    String msg = et.getText().toString().trim();
                    if (msg.isEmpty()) { toast("消息不能为空"); return; }
                    List<String> rooms = new ArrayList<>();
                    for (com.leshao.v3.model.ContactCard c : groups) rooms.add(c.username);
                    WmReflect.broadcastRooms(sCL, rooms, msg);
                    toast("已广播" + rooms.size() + "个群");
                }).setNegativeButton("取消", null).show();
    }

    static void showInvite() {
        try {
            com.leshao.v3.ui.ContactSelectorView.show(sAct, false,
                    com.leshao.v3.ui.ContactSelectorView.MODE_FRIEND, selected -> {
                        if (selected == null || selected.isEmpty()) { toast("未选择好友"); return; }
                        List<String> l = new ArrayList<>();
                        for (com.leshao.v3.model.ContactCard c : selected) l.add(c.username);
                        boolean ok = WmReflect.inviteMembers(sCL, sRoom, l);
                        toast(ok ? "邀请已发送 " + l.size() + " 人" : "邀请失败");
                    });
        } catch (Throwable t) {
            // 选择器不可用时回退到手动输入
            final EditText et = new EditText(sAct);
            et.setHint("wxid,逗号分隔");
            new AlertDialog.Builder(sAct).setTitle("邀请进群").setView(et)
                    .setPositiveButton("邀请", (d, w) -> {
                        String ids = et.getText().toString().trim();
                        if (ids.isEmpty()) { toast("请输入wxid"); return; }
                        List<String> l = new ArrayList<>();
                        for (String s : ids.split(",")) {
                            s = s.trim();
                            if (!s.isEmpty()) l.add(s);
                        }
                        boolean ok = WmReflect.inviteMembers(sCL, sRoom, l);
                        toast(ok ? "邀请已发送" : "邀请失败");
                    }).setNegativeButton("取消", null).show();
        }
    }

    // ===== 踢出成员 — 列表式 UI（复选框多选） =====
    static void showKick() {
        try {
            final List<String> ms = WmReflect.getMemberList(sCL, sRoom);
            if (ms == null || ms.isEmpty()) { toast("未获取到成员列表"); return; }

            final java.util.Set<Integer> selectedIdx = new java.util.HashSet<>();
            ListView lv = new ListView(sAct);
            lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

            lv.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return ms.size(); }
                @Override public Object getItem(int p) { return ms.get(p); }
                @Override public long getItemId(int p) { return p; }
                @Override public View getView(int p, View row, ViewGroup parent) {
                    String wxid = ms.get(p);
                    Object c = WmReflect.getContact(sCL, wxid);
                    String n = WmReflect.getRemark(c);
                    if (n == null || n.isEmpty()) n = WmReflect.getNickname(c);
                    if (n == null || n.isEmpty()) n = wxid;
                    if (row == null) {
                        LinearLayout l = new LinearLayout(sAct);
                        l.setOrientation(LinearLayout.HORIZONTAL);
                        l.setPadding(dp(8), dp(4), dp(8), dp(4));
                        android.widget.CheckBox cb = new android.widget.CheckBox(sAct);
                        cb.setPadding(0, 0, dp(8), 0);
                        l.addView(cb);
                        ImageView iv = new ImageView(sAct);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(dp(36), dp(36)));
                        l.addView(iv);
                        iv.setTag(android.R.id.icon);
                        TextView tv = new TextView(sAct);
                        tv.setGravity(Gravity.CENTER_VERTICAL);
                        tv.setPadding(dp(8), 0, 0, 0);
                        tv.setTextColor(AppColors.text1());
                        tv.setTextSize(13);
                        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(40), 1f);
                        l.addView(tv, lp2);
                        row = l;
                    }
                    android.widget.CheckBox cb = (android.widget.CheckBox) ((ViewGroup) row).getChildAt(0);
                    cb.setChecked(selectedIdx.contains(p));
                    cb.setOnCheckedChangeListener(null);
                    cb.setOnCheckedChangeListener((bv, isChecked) -> {
                        if (isChecked) selectedIdx.add(p); else selectedIdx.remove(p);
                    });
                    ImageView iv = (ImageView) ((ViewGroup) row).getChildAt(1);
                    iv.setImageBitmap(null);
                    WmReflect.loadAvatarInto(sCL, iv, wxid);
                    TextView tv = (TextView) ((ViewGroup) row).getChildAt(2);
                    tv.setText((p + 1) + ". " + n);
                    return row;
                }
            });

            new AlertDialog.Builder(sAct)
                    .setTitle("踢出成员 (共" + ms.size() + "人)")
                    .setView(lv)
                    .setPositiveButton("确认踢出(" + selectedIdx.size() + ")", (d, w) -> {
                        if (selectedIdx.isEmpty()) { toast("请至少选择一人"); return; }
                        int ok = 0;
                        for (int idx : selectedIdx) {
                            if (WmReflect.kickMember(sCL, sRoom, ms.get(idx))) ok++;
                        }
                        toast("已踢出 " + ok + "/" + selectedIdx.size() + " 人");
                    })
                    .setNegativeButton("取消", null).show();
        } catch (Exception e) {
            LogWriter.log(TAG, "showKick err: " + e.getMessage());
            toast("加载失败: " + e.getMessage());
        }
    }

    static void quitGroup() {
        new AlertDialog.Builder(sAct).setTitle("退出群聊")
                .setMessage("请通过群聊信息页右上角···退出\n(微信限制:退群需在群信息页操作)")
                .setPositiveButton("打开群信息", (d, w) -> {
                    try {
                        android.content.Intent i = new android.content.Intent();
                        i.setClassName("com.tencent.mm", "com.tencent.mm.chatroom.ui.ChatroomInfoUI");
                        i.putExtra("Chat_User", sRoom);
                        sAct.startActivity(i);
                    } catch (Exception e) { toast("无法打开"); }
                }).setNegativeButton("取消", null).show();
    }

    // ===== 导出全部群 =====
    static void exportAllRooms() {
        try {
            List<String> rooms = WmReflect.getAllChatRooms(sCL);
            // 兜底数据源已清空（原 ContactRepository 群列表），待重写
            if (rooms == null || rooms.isEmpty()) { toast("未获取到群列表"); return; }
            StringBuilder sb = new StringBuilder();
            sb.append("群ID,群名,成员数,群主\n");
            for (String r : rooms) {
                sb.append(r).append(",");
                try { sb.append(csv(WmReflect.getRoomDisplayName(sCL, r))); } catch (Exception ignored) { sb.append(""); }
                sb.append(",");
                try { sb.append(WmReflect.getMemberCount(sCL, r)); } catch (Exception ignored) { sb.append("?"); }
                sb.append(",");
                try { sb.append(csv(WmReflect.getRoomOwner(sCL, r))); } catch (Exception ignored) { sb.append(""); }
                sb.append("\n");
            }
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "WeChatMaster");
            dir.mkdirs();
            File f = new File(dir, "allrooms_" + System.currentTimeMillis() + ".csv");
            FileOutputStream fos = new FileOutputStream(f);
            try { fos.write(sb.toString().getBytes("UTF-8")); } finally { fos.close(); }
            toast("已导出全部群:" + f.getName() + " (" + rooms.size() + "个)");
        } catch (Exception e) {
            LogWriter.log(TAG, "exportAllRooms err: " + e.getMessage());
            toast("导出失败: " + e.getMessage());
        }
    }

    static String csv(String s) {
        if (s == null) return "";
        return s.replace(",", "，").replace("\n", " ");
    }

    static int dp(int d) {
        return (int) (d * sAct.getResources().getDisplayMetrics().density);
    }

    static void toast(String m) {
        Toast.makeText(sAct, m, Toast.LENGTH_SHORT).show();
    }
}
