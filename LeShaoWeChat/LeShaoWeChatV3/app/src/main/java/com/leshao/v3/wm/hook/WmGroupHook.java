package com.leshao.v3.wm.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
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
import java.util.List;

/**
 * 群管理功能 — 仅保留查看成员wxid。
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
        LogWriter.log(TAG, "showGroupPanel room=" + room + " act=" + (act != null ? act.getClass().getSimpleName() : "null"));
        sAct = act;
        sCL = cl;
        sRoom = room;
        sWM = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
        if (room == null) { LogWriter.log(TAG, "showGroupPanel skip: room null"); return; }
        try {
            if (!WmReflect.isChatRoom(cl, room)) { LogWriter.log(TAG, "showGroupPanel skip: not chatroom"); return; }
        } catch (Throwable e) {
            LogWriter.log(TAG, "isChatRoom failed: " + e.getMessage() + " (proceeding)");
        }
        LogWriter.log(TAG, "showGroupPanel isChatRoom passed, sPanelOn=" + sPanelOn);
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

    public static void setGroupFloat(com.leshao.v3.wm.utils.WmUi.DragFloat f) {
        if (sGrpFloat != null) sGrpFloat.removeFromWindow();
        sGrpFloat = f;
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

        btns.addView(com.leshao.v3.wm.utils.WmUi.makeHeader(sAct, "🛡 群管理",
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
        int cnt = 0;
        try { cnt = WmReflect.getMemberCount(sCL, sRoom); } catch (Throwable t) {
            if (!t.getMessage().contains("Kernel not initialized")) {
                LogWriter.log(TAG, "makeRoomSubtitle getMemberCount err: " + t.getMessage());
            }
        }
        String display = null;
        try { display = WmReflect.getRoomDisplayName(sCL, sRoom); } catch (Throwable t) {
            if (!t.getMessage().contains("Kernel not initialized")) {
                LogWriter.log(TAG, "makeRoomSubtitle getRoomDisplayName err: " + t.getMessage());
            }
        }
        if (display == null || display.isEmpty()) display = sRoom;
        return "群聊名称 " + display + "\n成员人数 " + cnt + "人";
    }

    /** 群管理按钮列表，供自身面板与聊天页⚡面板共用 */
    static void appendGroupButtons(LinearLayout btns) {
        if (WmPrefs.isGrpWxid()) btns.addView(WmUi.makeBtn(sAct, "👀 查看成员wxid", WmGroupHook::showMemberWxid));
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

    static int dp(int d) {
        return (int) (d * sAct.getResources().getDisplayMetrics().density);
    }

    static void toast(String m) {
        Toast.makeText(sAct, m, Toast.LENGTH_SHORT).show();
    }
}
