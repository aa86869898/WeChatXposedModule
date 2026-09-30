package com.leshao.v3.hook;

import de.robv.android.xposed.XposedHelpers;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;

import java.util.ArrayList;
import java.util.List;

/**
 * 乐少万群定时群发（功能24）
 * 模块联系人选择器勾选群 + 内容 + 定时 → 群发。
 */
public class WxMasterFeatures {

    private static final String TAG = "WxMaster";

    private static ClassLoader sWxCl;

    // 静态 Handler + 可取消 token: 避免定时任务持有 Activity, 并在取消/关闭时真正移除回调
    private static final Handler sTimerHandler = new Handler(Looper.getMainLooper());
    private static final Object sTimerLock = new Object();
    private static Runnable sPendingSend;

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
            // 禁止全量群发降级: 选择器异常时提示失败并中止, 避免误发到全部群聊
            LogWriter.log(TAG, "batchSend 选择器异常, 中止(不降级全量): " + t.getMessage());
            toast(act, "打开群选择失败，已中止");
        }
    }

    /** 取消尚未触发的定时群发任务。 */
    private static void cancelPendingSend() {
        synchronized (sTimerLock) {
            if (sPendingSend != null) {
                sTimerHandler.removeCallbacks(sPendingSend);
                sPendingSend = null;
                LogWriter.log(TAG, "定时群发已取消");
            }
        }
    }

    /** 定时群发对话框：内容 + 定时时间 → 发送（v1013 M3 主题） */
    private static void showScheduleDialog(final Activity act, final ClassLoader cl, final List<String> targets) {
        if (act == null) return;
        try { com.leshao.v3.ui.AppColors.refresh(); } catch (Throwable ignored) {}

        final long[] triggerMs = {0};

        android.widget.LinearLayout root = com.leshao.v3.ui.widgets.M3Page.root(act);
        root.addView(com.leshao.v3.ui.widgets.M3Page.section(act, "乐少万群定时群发",
                "将发送到 " + targets.size() + " 个群"));

        android.widget.LinearLayout card = com.leshao.v3.ui.widgets.M3Page.card(act);
        card.addView(com.leshao.v3.ui.widgets.M3Page.fieldLabel(act, "群发内容"));
        final EditText input = com.leshao.v3.ui.widgets.M3Page.input(act, "输入要群发的消息内容");
        input.setSingleLine(false);
        input.setHorizontallyScrolling(false);
        input.setMinLines(2);
        input.setMaxLines(5);
        com.leshao.v3.ui.widgets.M3Page.enableVerticalScroll(input);
        card.addView(input);
        card.addView(com.leshao.v3.ui.widgets.M3Page.spacer(act, 8));

        final TextView timeLabel = com.leshao.v3.ui.widgets.M3Page.note(act, "发送时间：立即发送");
        card.addView(timeLabel);
        card.addView(com.leshao.v3.ui.widgets.M3Page.spacer(act, 8));
        card.addView(com.leshao.v3.ui.widgets.M3Page.ghostButton(act, "选择定时时间",
                () -> showDateTimePicker(act, triggerMs, timeLabel)));
        root.addView(card);

        final Runnable doSend = () -> {
            String msg = input.getText().toString().trim();
            if (msg.isEmpty()) { toast(act, "消息不能为空"); return; }
            if (triggerMs[0] > 0 && triggerMs[0] > System.currentTimeMillis()) {
                long delay = triggerMs[0] - System.currentTimeMillis();
                cancelPendingSend();
                synchronized (sTimerLock) {
                    sPendingSend = () -> {
                        synchronized (sTimerLock) { sPendingSend = null; }
                        try { sendBroadcast(cl, targets, msg); }
                        catch (Throwable t) { LogWriter.log(TAG, "定时群发失败: " + t.getMessage()); }
                    };
                    sTimerHandler.postDelayed(sPendingSend, delay);
                }
                toast(act, "已定时, " + delay / 1000 + " 秒后发送到 " + targets.size() + " 个群");
            } else {
                boolean ok = sendBroadcast(cl, targets, msg);
                toast(act, ok ? "已发送到 " + targets.size() + " 个群" : "发送失败");
            }
        };

        AlertDialog.Builder b = new AlertDialog.Builder(act, m3Theme(act));
        b.setView(com.leshao.v3.ui.InsetsUtil.window(null, root, 0.92f, 0.7f));
        b.setCancelable(true);
        AlertDialog dlg = b.create();
        com.leshao.v3.ui.InsetsUtil.center(dlg, 0.92f, 0.7f);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            com.leshao.v3.ui.InsetsUtil.transparentWindow(w);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        View sendBtn = com.leshao.v3.ui.widgets.M3Page.button(act, "发送", () -> {
            doSend.run();
            dlg.dismiss();
        });
        View cancelBtn = com.leshao.v3.ui.widgets.M3Page.ghostButton(act, "取消", () -> {
            cancelPendingSend();
            dlg.dismiss();
        });
        root.addView(com.leshao.v3.ui.widgets.M3Page.buttonRow(act, sendBtn, cancelBtn));

        // 对话框关闭(含点外部/返回)时取消未触发的定时任务, 防止悬挂
        dlg.setOnDismissListener(d -> cancelPendingSend());

        com.leshao.v3.ui.InsetsUtil.clearDialogShell(dlg);
        dlg.show();
        com.leshao.v3.ui.InsetsUtil.clearDialogShell(dlg);
    }

    private static int m3Theme(Activity act) {
        try {
            return com.leshao.v3.ui.AppColors.isDarkMode()
                    ? android.R.style.Theme_DeviceDefault_NoActionBar
                    : android.R.style.Theme_DeviceDefault_Light_NoActionBar;
        } catch (Throwable ignored) {
            return android.R.style.Theme_DeviceDefault_Light_NoActionBar;
        }
    }

    private static void showDateTimePicker(final Activity act, final long[] result, final TextView label) {
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(act, m3Theme(act), (view, year, month, dayOfMonth) -> {
            final int y = year, mo = month, d = dayOfMonth;
            new android.app.TimePickerDialog(act, m3Theme(act), (tv, hour, minute) -> {
                cal.set(y, mo, d, hour, minute, 0);
                result[0] = cal.getTimeInMillis();
                label.setText("发送时间: " + new java.text.SimpleDateFormat("MM-dd HH:mm")
                        .format(new java.util.Date(result[0])));
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show();
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    /** 群发核心：先尝试微信原生群发接口(多群文本)，失败回退逐群发送 */
    private static boolean sendBroadcast(ClassLoader cl, List<String> rooms, String content) {
        if (sWxCl != null) cl = sWxCl;
        if (rooms == null || rooms.isEmpty() || content == null) return false;
        if (sendViaMultiTarget(cl, rooms, content)) return true;
        int ok = 0;
        for (String room : rooms) {
            try {
                GroupFeatures.sendTextMessage(cl, room, content);
                ok++;
            } catch (Throwable ignored) {}
        }
        return ok > 0;
    }

    /**
     * 8.0.78(3180) 多群文本: qs5.v5.hj(atStr, usersCsv, extra) / gj(str1,str2,str3,Z)。
     * 旧 kl5.s5.qj(content, csv) 已失效(kl5.s5 不存在)。
     */
    private static boolean sendViaMultiTarget(ClassLoader cl, List<String> rooms, String content) {
        if (sWxCl != null) cl = sWxCl;
        try {
            Object mgr = com.leshao.v3.wm.utils.WmReflect.getSendMsgMgr(cl);
            if (mgr == null) return false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rooms.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(rooms.get(i));
            }
            String csv = sb.toString();
            Throwable lastErr = null;
            try {
                XposedHelpers.callMethod(mgr, "hj", (Object) null, csv, (Object) null);
                LogWriter.log(TAG, "sendViaMultiTarget hj ok, rooms=" + rooms.size());
                return true;
            } catch (Throwable t) {
                lastErr = t;
            }
            try {
                XposedHelpers.callMethod(mgr, "gj", (Object) null, csv, (Object) null, true);
                LogWriter.log(TAG, "sendViaMultiTarget gj ok, rooms=" + rooms.size());
                return true;
            } catch (Throwable t) {
                lastErr = t;
            }
            LogWriter.log(TAG, "sendViaMultiTarget fail: " + (lastErr != null ? lastErr.getMessage() : "no method"));
            return false;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendViaMultiTarget err: " + t.getMessage());
            return false;
        }
    }

    private static void toast(Activity act, String msg) {
        if (act != null) Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
