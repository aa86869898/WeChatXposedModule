package com.leshao.v3.wm.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.DexKitHelper;
import com.leshao.v3.hook.TtsVoiceSender;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.wm.utils.WmPrefs;
import com.leshao.v3.wm.utils.WmReflect;
import com.leshao.v3.wm.utils.WmUi;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 聊天窗口功能注入 — 复刻自微信大师 ChatFeatureHook
 * 标题栏右上角⚡按钮 → 弹出功能面板(14项)
 */
public class WmChatHook {
    private static com.leshao.v3.wm.utils.WmUi.DragFloat sFloatIcon;
    private static Dialog sPanelDialog;
    private static WindowManager sWM;
    private static String sUser;
    private static ClassLoader sCL;
    private static Activity sAct;
    private static volatile boolean sPanelShow;
    private static final Handler sH = new Handler(Looper.getMainLooper());

    private static final String TAG = "WmChat";
    private static BroadcastReceiver sMassSendReceiver;
    private static Context sCtx;
    private static volatile boolean sMassSendRunning;
    private static android.view.View sMoreIcon;
    private static boolean sMoreAdded = false;
    private static Dialog sMoreDialog;
    private static int sMoreMarginPx = -1;

    public static void showTitleBtn(Activity act, ClassLoader cl, String user) {
        // 幂等：轮询 reconciler 每 400ms 调用，已注入且同一会话时跳过重建避免闪烁
        if (sFloatIcon != null && sUser != null && sUser.equals(user)
                && sAct != null && !sAct.isFinishing()) {
            if (act != null) sAct = act;
            return;
        }
        dismissTitleBtn();
        sAct = act;
        sCL = cl;
        sUser = user;
        sCtx = act.getApplicationContext();
        sWM = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
        ensureReceiverRegistered();
        recoverMassSendTask();
        if (user == null) return;

        if (com.leshao.v3.service.ActivationManager.isCurrentUserBlocked()) {
            LogWriter.log(TAG, "float icon suppressed: user blacklisted");
            return;
        }
        LogWriter.log(TAG, "chat window opened user=" + user);

        com.leshao.v3.wm.utils.WmUi.DragFloat f = new com.leshao.v3.wm.utils.WmUi.DragFloat(
                act, sWM, "⚡", AppColors.accent(), "float_chat",
                () -> { if (sPanelShow) hidePanel(); else showPanel(); });
        f.addToWindow();
        sFloatIcon = f;
        ensureMoreButton(act);
    }

    public static void dismissTitleBtn() {
        hidePanel();
        removeMoreButton();
        if (sFloatIcon != null) {
            sFloatIcon.removeFromWindow();
            sFloatIcon = null;
        }
        sAct = null;
    }

    public static void showPanelInline(Activity act) {
        if (sAct == null || sAct.isFinishing()) {
            sAct = act;
        }
        if (sAct == null || sAct.isFinishing()) return;
        sPanelShow = true;
        showPanel();
    }

    // ===== 功能面板 =====
    static void showPanel() {
        if (sAct == null || sAct.isFinishing()) return;
        LinearLayout panel = com.leshao.v3.wm.utils.WmUi.makePanel(sAct);
        GradientDrawable bsBg = new GradientDrawable();
        bsBg.setColor(AppColors.card());
        bsBg.setCornerRadius(dp(18));
        panel.setBackground(bsBg);
        boolean isGroup = sUser != null && (sUser.endsWith("@chatroom") || sUser.endsWith("@im.chatroom"));
        String displayName = sUser;
        try {
            if (isGroup) {
                String dn = com.leshao.v3.wm.utils.WmReflect.getRoomDisplayName(sCL, sUser);
                if (dn != null && !dn.isEmpty()) displayName = dn;
            } else {
                Object c = com.leshao.v3.wm.utils.WmReflect.getContact(sCL, sUser);
                String r = com.leshao.v3.wm.utils.WmReflect.getRemark(c);
                if (r != null && !r.isEmpty()) displayName = r;
                else {
                    String n = com.leshao.v3.wm.utils.WmReflect.getNickname(c);
                    if (n != null && !n.isEmpty()) displayName = n;
                }
            }
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || !msg.contains("Kernel not initialized")) {
                LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + msg);
            }
        }

        ScrollView sv = new ScrollView(sAct);
        LinearLayout btns = new LinearLayout(sAct);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.setPadding(0, 0, 0, dp(0));

        btns.addView(com.leshao.v3.wm.utils.WmUi.makeHeader(sAct,
                "⚡ 实用工具", displayName));

        if (WmPrefs.isBatchSend()) btns.addView(WmUi.makeBtn(sAct, "乐少万群定时群发", WmChatHook::showMassSend));
        if (WmPrefs.isExportChat()) btns.addView(WmUi.makeBtn(sAct, "📤 导出聊天", WmChatHook::exportChat));
        if (WmPrefs.isAutoVoice()) btns.addView(makeToggleRow("🔊 语音自动播放", "auto_voice"));

        if (isGroup) {
            try {
                com.leshao.v3.wm.hook.WmGroupHook.bind(sAct, sCL, sUser);
                btns.addView(WmUi.makeDivider(sAct));
                btns.addView(WmUi.makeHeader(sAct, "🛡 群管理", com.leshao.v3.wm.hook.WmGroupHook.makeRoomSubtitle()));
                com.leshao.v3.wm.hook.WmGroupHook.appendGroupButtons(btns);
            } catch (Throwable t) {
                LogWriter.log(TAG, "WmChatHook group panel err: " + t.getMessage());
            }
        }

        sv.addView(btns);
        panel.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        panel.addView(com.leshao.v3.wm.utils.WmUi.makePrimaryBtn(sAct, "✕ 收起面板",
                WmChatHook::hidePanel));

        Dialog dialog = new Dialog(sAct);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> { sPanelShow = false; });

        dialog.show();
        applyPanelWindow(dialog);
        sPanelDialog = dialog;
        sPanelShow = true;
    }

    private static void applyPanelWindow(Dialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) return;
        int pw = dp(250);
        int ph = dp(560);
        w.setLayout(pw, ph);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0.05f;

        if (sFloatIcon != null) {
            int[] loc = new int[2];
            try { sFloatIcon.btn.getLocationOnScreen(loc); } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            int fx = loc[0] + sFloatIcon.btn.getWidth() / 2 - pw / 2;
            int fy = loc[1] - ph - dp(8);
            int screenW = sAct.getResources().getDisplayMetrics().widthPixels;
            int screenH = sAct.getResources().getDisplayMetrics().heightPixels;
            if (fx < 0) fx = dp(5);
            if (fx + pw > screenW) fx = screenW - pw - dp(5);
            if (fy < 0) fy = dp(5);
            if (fy + ph > screenH) fy = screenH - ph - dp(5);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = fx;
            lp.y = fy;
        } else {
            lp.gravity = Gravity.CENTER;
        }
        w.setAttributes(lp);
    }

    static void hidePanel() {
        if (sPanelDialog != null) {
            try { sPanelDialog.dismiss(); } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            sPanelDialog = null;
        }
        sPanelShow = false;
    }


    // ===== 标题栏右上角 自绘竖向 ⋮ 按钮 (已移除: 8.0.78 原生三点+MsgExport注入足够, 悬浮窗口会引发闪退) =====
    private static void ensureMoreButton(Activity act) {
        // 圆形三点已移除：微信 8.0.78 标题栏自带右上角溢出菜单，
        // 配合 MsgExport 的 o.r 注入即可扩展功能，无需额外悬浮按钮。
        // 悬浮 TYPE_APPLICATION_PANEL 窗口在 Activity 切换时 token 失效会导致闪退。
    }

    /** 扫描聊天标题栏右侧的可点击图标，返回"原生图标簇左侧"对应的右边距(px)。
     *  找不到或未布局返回 -1。 */
    private static int computeMoreRightMarginPx(Activity act) {
        try {
            View decor = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            if (decor == null || decor.getWidth() <= 0 || !decor.isShown()) return -1;
            int screenW = decor.getWidth();
            int sb = statusBarHeight(act);
            int topBand = sb - dp(4);
            int bottomBand = sb + dp(56);
            final java.util.List<int[]> rects = new java.util.ArrayList<>();
            collectTitleIconRects(decor, 0, 0, topBand, bottomBand, rects);
            if (rects.isEmpty()) return -1;
            java.util.Collections.sort(rects, (a, b) -> Integer.compare(a[0], b[0]));
            int[] rightMost = rects.get(rects.size() - 1);
            int clusterLeft = rightMost[0];
            for (int i = rects.size() - 2; i >= 0; i--) {
                int[] r = rects.get(i);
                if (r[1] + dp(16) >= clusterLeft) clusterLeft = r[0];
                else break;
            }
            int margin = screenW - clusterLeft + dp(8);
            if (margin < dp(6)) margin = dp(6);
            LogWriter.log(TAG, "more native clusterL=" + clusterLeft + " icons=" + rects.size()
                    + " margin=" + margin);
            return margin;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void collectTitleIconRects(View v, int baseLeft, int baseTop,
                                              int topBand, int bottomBand, java.util.List<int[]> out) {
        if (v == null) return;
        int left = baseLeft + v.getLeft();
        int top = baseTop + v.getTop();
        int right = left + v.getWidth();
        int bottom = top + v.getHeight();
        if (bottom < topBand || top > bottomBand) return;
        int w = v.getWidth(), h = v.getHeight();
        boolean clickable = false;
        try { clickable = v.isClickable(); } catch (Throwable ignored) {}
        if (clickable && w > 0 && h > 0 && w <= dp(150) && h <= dp(120)) {
            int cy = (top + bottom) / 2;
            if (cy >= topBand && cy <= bottomBand) {
                out.add(new int[]{left, right});
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = g.getChildAt(i);
                if (c == null || c.getVisibility() != View.VISIBLE) continue;
                collectTitleIconRects(c, left, top, topBand, bottomBand, out);
            }
        }
    }

    /** 微信布局完成/标题栏稳定后，按原生图标簇重排 ⋮ 位置，确保不遮挡 */
    private static void repositionMoreButton(Activity act) {
        try {
            if (!sMoreAdded || sMoreIcon == null || act == null) return;
            int m = computeMoreRightMarginPx(act);
            if (m <= 0 || m == sMoreMarginPx) return;
            sMoreMarginPx = m;
            WindowManager.LayoutParams wp = (WindowManager.LayoutParams) sMoreIcon.getLayoutParams();
            if (wp == null) return;
            wp.x = m;
            sWM.updateViewLayout(sMoreIcon, wp);
            LogWriter.log(TAG, "more icon repositioned margin=" + m);
        } catch (Throwable t) {
            LogWriter.log(TAG, "more icon reposition err: " + t.getMessage());
        }
    }

    private static void removeMoreButton() {
        dismissMoreMenu();
        if (sMoreAdded && sMoreIcon != null) {
            try { sWM.removeView(sMoreIcon); } catch (Throwable ignored) {}
        }
        sMoreIcon = null;
        sMoreAdded = false;
        sMoreMarginPx = -1;
    }

    private static void showMoreMenu() {
        if (sAct == null || sAct.isFinishing()) return;
        if (sMoreDialog != null) return;
        final Activity act = sAct;
        boolean dark = (act.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bgCard = dark ? 0xFF2A2A2E : 0xFFFFFFFF;
        int fgText = dark ? 0xFFE4E4E8 : 0xFF1D1D1F;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgCard);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), dark ? 0xFF3A3A3E : 0xFFE5E5EA);
        box.setBackground(bg);
        box.setPadding(dp(4), dp(6), dp(4), dp(6));
        if (WmPrefs.isExportChat()) {
            box.addView(makeMoreRow(act, "导出聊天记录 (TXT)", fgText, v -> { dismissMoreMenu(); exportChat(); }));
            box.addView(makeMoreRow(act, "导出聊天记录 (HTML)", fgText, v -> { dismissMoreMenu(); exportChatHtml(); }));
        }
        box.addView(makeMoreRow(act, "更多功能", fgText, v -> { dismissMoreMenu(); showPanel(); }));
        Dialog d = new Dialog(act);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        WindowManager.LayoutParams lp = w.getAttributes();
        w.setLayout(dp(200), WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.x = sMoreMarginPx > 0 ? sMoreMarginPx : dp(8);
        lp.y = statusBarHeight(act) + dp(48);
        lp.dimAmount = 0f;
        w.setAttributes(lp);
        d.setCanceledOnTouchOutside(true);
        d.setOnDismissListener(dd -> { sMoreDialog = null; });
        d.show();
        sMoreDialog = d;
        LogWriter.log(TAG, "more menu shown");
    }

    private static void dismissMoreMenu() {
        if (sMoreDialog != null) {
            try { sMoreDialog.dismiss(); } catch (Throwable ignored) {}
            sMoreDialog = null;
        }
    }

    private static View makeMoreRow(Activity act, String text, int fg, View.OnClickListener click) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(18), dp(13), dp(18), dp(13));
        tv.setOnClickListener(click);
        return tv;
    }

    private static int statusBarHeight(Activity act) {
        try {
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(24);
    }

    static void exportChatHtml() {
        new Thread(() -> {
            int count = exportChatHtmlReal();
            sH.post(() -> {
                if (count > 0) {
                    toast("已导出" + count + "条消息到 /sdcard/WeChatMaster/ (HTML)");
                } else if (count == -1) {
                    toast("数据库未就绪,请稍后重试");
                } else {
                    toast("该会话无消息记录");
                }
            });
        }).start();
    }

    static int exportChatHtmlReal() {
        Cursor c = null;
        FileOutputStream fos = null;
        try {
            c = rawQueryMsg(
                "SELECT content, createTime, isSend, type FROM message WHERE talker=? ORDER BY createTime ASC LIMIT 50000",
                new String[]{sUser});
            if (c == null) return -1;
            if (c.getCount() == 0) return 0;

            File dir = new File(Environment.getExternalStorageDirectory(), "WeChatMaster");
            dir.mkdirs();
            File f = new File(dir, "chat_" + Math.abs(sUser.hashCode()) + "_" + System.currentTimeMillis() + ".html");
            fos = new FileOutputStream(f);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            fos.write(("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>" + escHtml(sUser)
                + "</title><style>body{font-family:sans-serif;max-width:800px;margin:auto;padding:10px}"
                + ".me{color:#07C160;text-align:right}.other{color:#333}.time{font-size:10px;color:#999}"
                + ".bubble{display:inline-block;max-width:70%;padding:8px 12px;border-radius:8px;margin:2px 0}"
                + ".me .bubble{background:#95EC69}.other .bubble{background:#fff;border:1px solid #eee}"
                + "</style></head><body><h2>" + escHtml(sUser) + "</h2><hr>\n").getBytes("UTF-8"));
            int cnt = 0;
            while (c.moveToNext()) {
                try {
                    String content = c.getString(0); if (content == null) content = "";
                    long tm = c.getLong(1);
                    int isSend = c.getInt(2);
                    int type = c.getInt(3);
                    String cls = isSend == 1 ? "me" : "other";
                    String row = "<div class='" + cls + "'><div class='bubble'>" + escHtml(content) + "</div>"
                            + "<div class='time'>" + sdf.format(new Date(tm)) + " [" + typeMap(type) + "]</div></div>\n";
                    fos.write(row.getBytes("UTF-8"));
                    cnt++;
                } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            }
            fos.write("</body></html>".getBytes("UTF-8"));
            return cnt;
        } catch (Exception e) {
            LogWriter.log(TAG, "exportChatHtml err: " + e.getMessage());
            return -1;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable t) { LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
            try { if (c != null) c.close(); } catch (Throwable t) { LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
        }
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\n", "<br>");
    }

    // ===== 文件选择器 (系统文件管理器) =====

    interface PickCallback { void onPick(String path); }
    private static final int REQ_PICK_FILE = 0x7F01;
    private static PickCallback sPendingPickCallback;
    private static boolean sActivityResultHooked;

    static void pickImage(PickCallback cb) {
        launchSystemFilePicker("image/*", cb);
    }

    static void pickAudio(PickCallback cb) {
        launchSystemFilePicker("audio/*", cb);
    }

    private static void launchSystemFilePicker(String mimeType, PickCallback cb) {
        ensureActivityResultHook();
        sPendingPickCallback = cb;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        try {
            sAct.startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Exception e) {
            sPendingPickCallback = null;
        }
    }

    private static void ensureActivityResultHook() {
        if (sActivityResultHooked) return;
        sActivityResultHooked = true;
        try {
            Method m = Activity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        int requestCode = (int) param.args[0];
                                        int resultCode = (int) param.args[1];
                                        Intent data = (Intent) param.args[2];
                                        if (requestCode == REQ_PICK_FILE && sPendingPickCallback != null) {
                                            String path = null;
                                            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                                                path = copyUriToTemp((Activity) param.thisObject, data.getData());
                                            }
                                            PickCallback cb = sPendingPickCallback;
                                            sPendingPickCallback = null;
                                            cb.onPick(path);
                                        }
                    } catch (Throwable e) {
                        LogWriter.log("WmChat", "cb err: " + e);
                    }
                }
            });
        } catch (Exception e) {
            LogWriter.log(TAG, "onActivityResult hook failed: " + e.getMessage());
        }
    }

    /** 将 content:// URI 复制到应用私有目录 /data/data/<pkg>/files/wm_picker/ 返回本地路径 */
    private static String copyUriToTemp(Activity act, Uri uri) {
        try {
            String fileName = "picked_" + System.currentTimeMillis();
            Cursor cursor = act.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) fileName = cursor.getString(idx);
                    }
                } finally { cursor.close(); }
            }
            File tmpDir = new File(act.getFilesDir(), "wm_picker");
            if (!tmpDir.exists()) tmpDir.mkdirs();
            File out = new File(tmpDir, fileName);
            InputStream is = act.getContentResolver().openInputStream(uri);
            if (is == null) {
                LogWriter.log(TAG, "copyUriToTemp: openInputStream null");
                return null;
            }
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                try { fos.close(); } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
                try { is.close(); } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            }
            LogWriter.log(TAG, "copyUriToTemp ok: " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Exception e) {
            LogWriter.log(TAG, "copyUriToTemp err: " + e.getMessage());
            return null;
        }
    }

    static String findVoice2Dir() {
        try {
            String dataDir = sAct.getFilesDir().getParentFile().getAbsolutePath();
            String[] parts = dataDir.split("/");
            String uinDir = "";
            for (String p : parts) {
                if (p.matches("[a-zA-Z0-9]{16,}")) { uinDir = p; break; }
            }
            if (uinDir.isEmpty()) uinDir = "voice2";
            return Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/tencent/MicroMsg/" + uinDir + "/voice2/";
        } catch (Exception e) {
            return Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/tencent/MicroMsg/voice2/";
        }
    }

    // ===== 数据库访问 =====

    private static Object sCachedDb;   // 缓存已打开的 WCDB 实例
    private static Method sCachedRawQueryMethod;

    /** 打开 WeChat 消息数据库并查询 */
    static Cursor rawQueryMsg(String sql, String[] args) {
        // 1. 优先使用缓存的 WCDB 实例
        if (sCachedDb != null && sCachedRawQueryMethod != null) {
            try { return (Cursor) sCachedRawQueryMethod.invoke(sCachedDb, sql, args); }
            catch (Throwable t) { sCachedDb = null; sCachedRawQueryMethod = null; LogWriter.log(TAG, "rawQueryMsg cached failed: " + t.getMessage()); }
        }

        // 2. 回退: VersionCompat 打开
        Object db2 = openWxDb();
        if (db2 != null) {
            Cursor c = tryRawQuery(db2, sql, args);
            if (c != null) { sCachedDb = db2; return c; }
        }

        LogWriter.log(TAG, "rawQueryMsg: all attempts failed");
        return null;
    }

    /** 尝试在 db 上调用 rawQuery(String, String[]) */
    private static Cursor tryRawQuery(Object db, String sql, String[] args) {
        for (Method m : db.getClass().getMethods()) {
            if (isRawQuery2(m)) {
                sCachedRawQueryMethod = m;
                Cursor c = invokeRawQuery(db, m, sql, args);
                if (c != null) return c;
            }
        }
        for (Method m : db.getClass().getDeclaredMethods()) {
            if (isRawQuery2(m)) {
                sCachedRawQueryMethod = m;
                Cursor c = invokeRawQuery(db, m, sql, args);
                if (c != null) return c;
            }
        }
        // 也尝试混淆后的方法名
        for (String mn : new String[]{"u", "rowQuery", "v", "w", "x", "y", "z"}) {
            for (Method m : db.getClass().getMethods()) {
                if (m.getName().equals(mn) && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class) {
                    sCachedRawQueryMethod = m;
                    Cursor c = invokeRawQuery(db, m, sql, args);
                    if (c != null) return c;
                }
            }
        }
        return null;
    }

    private static boolean isRawQuery2(Method m) {
        return m.getName().equals("rawQuery")
                && m.getParameterCount() == 2
                && m.getParameterTypes()[0] == String.class;
    }

    private static Cursor invokeRawQuery(Object db, Method m, String sql, String[] args) {
        try {
            m.setAccessible(true);
            return (Cursor) m.invoke(db, sql, args);
        } catch (Throwable t) {
            LogWriter.log(TAG, "invokeRawQuery err: " + t.getMessage());
            return null;
        }
    }

    static Object openWxDb() {
        try {
            Context appCtx = com.leshao.v3.ContextManager.getAppContext();
            if (appCtx == null) return null;
            long uin = getWxUin(appCtx);
            if (uin <= 0) return null;
            String imei = com.leshao.v3.hook.VersionCompat.getImei(sCL);
            String password = md5(imei + uin).substring(0, 7);
            String base = com.leshao.v3.hook.VersionCompat.getBaseDir(sCL, appCtx);
            String hash = com.leshao.v3.hook.VersionCompat.getDbHash(sCL, (int) uin);
            String dbPath = base + "MicroMsg/" + hash + "/EnMicroMsg.db";
            Class<?> dbOpener = com.leshao.v3.hook.VersionCompat.findDbOpenerClass(sCL);
            if (dbOpener == null) return null;
            return com.leshao.v3.hook.VersionCompat.openDatabase(dbOpener, dbPath, password);
        } catch (Throwable t) {
            LogWriter.log(TAG, "openWxDb err: " + t.getClass().getSimpleName());
            return null;
        }
    }

    static long getWxUin(Context ctx) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv != null) return Long.parseLong(uv.toString());
        } catch (Throwable t) { LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
        return 0;
    }

    static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    // 3. 导出聊天 — 从微信消息数据库读取真实记录
    static void exportChat() {
        new Thread(() -> {
            int count = exportChatReal();
            sH.post(() -> {
                if (count > 0) {
                    toast("已导出" + count + "条消息到 /sdcard/WeChatMaster/");
                } else if (count == -1) {
                    toast("数据库未就绪,请稍后重试");
                } else {
                    toast("该会话无消息记录");
                }
            });
        }).start();
    }

    static int exportChatReal() {
        Cursor c = null;
        FileOutputStream fos = null;
        try {
            c = rawQueryMsg(
                "SELECT content, createTime, isSend, type FROM message WHERE talker=? ORDER BY createTime ASC LIMIT 50000",
                new String[]{sUser});
            if (c == null) return -1;
            if (c.getCount() == 0) return 0;

            File dir = new File(Environment.getExternalStorageDirectory(), "WeChatMaster");
            dir.mkdirs();
            File f = new File(dir, "chat_" + Math.abs(sUser.hashCode()) + "_" + System.currentTimeMillis() + ".txt");
            fos = new FileOutputStream(f);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            fos.write(("================================\n聊天记录\n对象:" + sUser + "\n导出时间:" + sdf.format(new Date()) + "\n================================\n\n").getBytes("UTF-8"));
            int cnt = 0;
            while (c.moveToNext()) {
                try {
                    String content = c.getString(0); if (content == null) content = "";
                    long tm = c.getLong(1);
                    int isSend = c.getInt(2);
                    int type = c.getInt(3);
                    String line = sdf.format(new Date(tm)) + " "
                            + (isSend == 1 ? "[我]" : "[对方]")
                            + " [" + typeMap(type) + "] " + content + "\n";
                    fos.write(line.getBytes("UTF-8"));
                    cnt++;
                } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
            }
            fos.write(("\n================================\n共 " + cnt + " 条消息\n================================\n").getBytes("UTF-8"));
            return cnt;
        } catch (Exception e) {
            LogWriter.log(TAG, "exportChat err: " + e.getMessage());
            return -1;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable t) { LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
            try { if (c != null) c.close(); } catch (Throwable t) { LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
        }
    }

    static String typeMap(int t) {
        switch (t) {
            case 1: return "文本";
            case 3: return "图片";
            case 34: return "语音";
            case 43: return "视频";
            case 47: return "表情";
            case 49: return "链接";
            case 10002: return "已撤回";
            default: return "类型" + t;
        }
    }

    // ===== 面板开关控件 =====
    static LinearLayout makeToggleRow(String label, String prefKey) {
        LinearLayout row = new LinearLayout(sAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(AppColors.bg());
        rowBg.setCornerRadius(dp(12));
        row.setBackground(rowBg);

        TextView tv = new TextView(sAct);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text1());
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv, tvlp);

        Switch sw = CandyUi.newSwitch(sAct);
        sw.setChecked(WmPrefs.get(prefKey, WmPrefs.defaultFor(prefKey)));
        final String fKey = prefKey;
        sw.setOnCheckedChangeListener((v, on) -> {
            WmPrefs.set(fKey, on);
            // 语音自动播放开关 — 同步到 VoiceAutoPlay 引擎
            if ("auto_voice".equals(fKey)) {
                try {
                    com.leshao.v3.hook.VoiceAutoPlay.setEnabled(on);
                } catch (Throwable t) { LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + t.getMessage()); }
            }
            toast(label.replaceAll("[^\\u4e00-\\u9fa5]", "") + (on ? ":开" : ":关"));
        });
        row.addView(sw);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        rlp.setMargins(dp(8), 0, dp(8), dp(4));
        row.setLayoutParams(rlp);
        return row;
    }

    static int dp(int d) {
        return (int) (d * sAct.getResources().getDisplayMetrics().density);
    }

    static void toast(String m) {
        sH.post(() -> {
            if (sAct != null && !sAct.isFinishing()) {
                Toast.makeText(sAct, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ===== DatePicker + TimePicker helper =====

    /** 弹出 DatePickerDialog → TimePickerDialog 链式选择，结果存入 selectedTimeMs[0]，并更新 label */
    static void showDateTimePicker(final long[] selectedTimeMs, final TextView label) {
        final Calendar cal = Calendar.getInstance();
        if (selectedTimeMs[0] > 0) cal.setTimeInMillis(selectedTimeMs[0]);
        new DatePickerDialog(sAct, (view, year, month, dayOfMonth) -> {
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month);
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            new TimePickerDialog(sAct, (v2, hour, minute) -> {
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, minute);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                selectedTimeMs[0] = cal.getTimeInMillis();
                SimpleDateFormat fmt = new SimpleDateFormat("MM月dd日 HH:mm");
                label.setText("发送时间: " + fmt.format(new Date(selectedTimeMs[0])));
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ===== 乐少万群定时群发 =====

    private static final String[] MASS_TYPES =
        {"文本消息", "图片消息", "视频消息", "图文消息", "文视消息", "语音消息"};
    private static final String[] MASS_TYPE_KEYS =
        {"text", "image", "video", "image_text", "video_text", "voice"};
    private static final String[] MASS_TYPE_ICONS =
        {"💬", "🖼️", "🎬", "📄", "🎥", "🎵"};
    private static final String[] MASS_TYPE_DESCS =
        {"纯文字消息群发", "多张图片批量发送", "视频文件群发", "图片+文字组合", "视频+文字组合", "语音/音频文件"};

    // ==== 向导状态 ====
    private static int sWizardType = -1;
    private static final Set<String> sWizardTargets = new java.util.LinkedHashSet<>();
    private static long sWizardTimeMs = 0;
    private static String sWizardText = "";
    private static final List<String> sWizardImagePaths = new ArrayList<>();
    private static String sWizardVideoPath = null;
    private static String sWizardAudioPath = null;
    private static boolean sWizardDelay = true;

    private static void resetWizard() {
        sWizardType = -1;
        sWizardTargets.clear();
        sWizardTimeMs = 0;
        sWizardText = "";
        sWizardImagePaths.clear();
        sWizardVideoPath = null;
        sWizardAudioPath = null;
        sWizardDelay = true;
    }

    // ==== 液态玻璃霓虹糖果色彩体系 ====

    private static boolean isDarkMode() {
        if (sAct == null) return false;
        int flags = sAct.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static int surface()    { return isDarkMode() ? 0xEE0D1117 : 0xE6FFFFFF; }
    private static int glassCard()  { return isDarkMode() ? 0xCC1A1F2E : 0xBFFFFFFF; }
    private static int glassBorder(){ return isDarkMode() ? 0x33FFFFFF : 0x33FFFFFF; }
    private static int textPri()    { return isDarkMode() ? 0xFFF1F5F9 : 0xFF1F2937; }
    private static int textSec()    { return isDarkMode() ? 0xFF94A3B8 : 0xFF6B7280; }
    private static int textDim()    { return isDarkMode() ? 0xFF64748B : 0xFF9CA3AF; }
    private static int inputBg()    { return isDarkMode() ? 0x990D1117 : 0x99F1F5F9; }
    private static int dividerCol() { return isDarkMode() ? 0x20FFFFFF : 0x18000000; }

    // 霓虹糖果色（浅暗通用，饱和高明度）
    private static final int NEON_PINK   = 0xFFFF2D87;
    private static final int NEON_BLUE   = 0xFF3B82F6;
    private static final int NEON_PURPLE = 0xFF8B5CF6;
    private static final int NEON_CYAN   = 0xFF06B6D4;
    private static final int NEON_GREEN  = 0xFF10B981;
    private static final int NEON_ORANGE = 0xFFF59E0B;
    private static final int NEON_RED    = 0xFFEF4444;
    private static final int NEON_MINT   = 0xFF34D399;
    private static final int NEON_LAVENDER = 0xFFA78BFA;

    private static final int[] NEON_PALETTE = {
        NEON_PINK, NEON_BLUE, NEON_PURPLE, NEON_CYAN,
        NEON_GREEN, NEON_ORANGE, NEON_MINT
    };

    private static int neonColor(int idx) { return NEON_PALETTE[idx % NEON_PALETTE.length]; }
    private static int neonColorDim(int idx) {
        int c = NEON_PALETTE[idx % NEON_PALETTE.length];
        return (c & 0x00FFFFFF) | 0x28000000;
    }

    // ==== 通用 UI 工厂 ====

    // 液态玻璃卡片
    private static LinearLayout makeGlassCard() {
        LinearLayout card = new LinearLayout(sAct);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(glassCard());
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), glassBorder());
        card.setBackground(bg);
        card.setElevation(dp(2));
        return card;
    }

    // 霓虹强调卡片（带彩色边框发光效果）
    private static LinearLayout makeNeonCard(int color) {
        LinearLayout card = new LinearLayout(sAct);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(glassCard());
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2), (color & 0x00FFFFFF) | 0x40000000);
        card.setBackground(bg);
        card.setElevation(dp(4));
        return card;
    }

    // 玻璃输入框背景
    private static GradientDrawable makeGlassInputBg() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(inputBg());
        gd.setCornerRadius(dp(14));
        gd.setStroke(dp(1), glassBorder());
        return gd;
    }

    // 霓虹实心按钮
    private static Button makeNeonBtn(String text, int color) {
        Button btn = new Button(sAct);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setAllCaps(false);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        btn.setBackground(bg);
        btn.setElevation(dp(3));
        btn.setPadding(dp(24), 0, dp(24), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        lp.setMargins(0, dp(6), 0, dp(6));
        btn.setLayoutParams(lp);
        return btn;
    }

    // 霓虹描边按钮
    private static Button makeNeonOutlineBtn(String text, int color) {
        Button btn = new Button(sAct);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setStroke(dp(2), color);
        bg.setCornerRadius(dp(14));
        bg.setColor(0x00000000);
        btn.setBackground(bg);
        btn.setTextColor(color);
        btn.setPadding(dp(18), 0, dp(18), 0);
        return btn;
    }

    // 玻璃文字按钮
    private static Button makeGlassTextBtn(String text, int color) {
        Button btn = new Button(sAct);
        btn.setText(text);
        btn.setTextSize(13);
        btn.setAllCaps(false);
        btn.setTextColor(color);
        btn.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        btn.setBackground(bg);
        return btn;
    }

    // 步骤指示器
    private static void addStepIndicator(LinearLayout parent, int totalSteps) {
        int current = 0;
        if (totalSteps == 2) current = 1;
        else if (totalSteps == 3 && parent.getTag() instanceof Integer) current = (Integer) parent.getTag();
        else current = totalSteps - 1;

        LinearLayout stepRow = new LinearLayout(sAct);
        stepRow.setOrientation(LinearLayout.HORIZONTAL);
        stepRow.setGravity(Gravity.CENTER);
        stepRow.setPadding(0, dp(8), 0, dp(24));

        String[] labels = {"类型", "内容", "目标"};
        for (int i = 0; i < 3; i++) {
            // 圆点
            int size = dp(32);
            TextView dot = new TextView(sAct);
            dot.setText(String.valueOf(i + 1));
            dot.setTextSize(13);
            dot.setGravity(Gravity.CENTER);
            dot.setTypeface(null, android.graphics.Typeface.BOLD);
            GradientDrawable dotBg = new GradientDrawable();
            if (i < current) {
                dotBg.setColor(NEON_GREEN);
                dot.setTextColor(0xFFFFFFFF);
            } else if (i == current) {
                dotBg.setColor(neonColor(i));
                dot.setTextColor(0xFFFFFFFF);
            } else {
                dotBg.setColor(0x00000000);
                dotBg.setStroke(dp(2), glassBorder());
                dot.setTextColor(textDim());
            }
            dotBg.setCornerRadius(size);
            dot.setBackground(dotBg);
            dot.setElevation(i == current ? dp(4) : 0);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(size, size);
            stepRow.addView(dot, dlp);

            // 标签
            TextView label = new TextView(sAct);
            label.setText(labels[i]);
            label.setTextSize(9);
            label.setTextColor(i <= current ? textPri() : textDim());
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(size, dp(18));
            llp.setMargins(0, 0, dp(2), 0);
            stepRow.addView(label, llp);

            // 连接线
            if (i < 2) {
                View line = new View(sAct);
                line.setBackgroundColor(i < current ? NEON_GREEN : dividerCol());
                LinearLayout.LayoutParams lilp = new LinearLayout.LayoutParams(dp(24), dp(2));
                lilp.setMargins(dp(6), 0, dp(6), 0);
                stepRow.addView(line, lilp);
            }
        }
        parent.addView(stepRow);
    }

    // 分区标题
    private static TextView makeSectionTitle(String text) {
        TextView tv = new TextView(sAct);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(textPri());
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(18), 0, dp(10));
        return tv;
    }

    // ==================== 群发入口 ====================

    public static void showMassSendFromCorner(Activity act, ClassLoader cl) {
        sAct = act;
        sCL = cl;
        sCtx = act.getApplicationContext();
        ensureReceiverRegistered();
        showMassSend();
    }

    // ==================== Step 1: 类型选择 ====================

    static void showMassSend() {
        if (sAct == null || sAct.isFinishing()) return;
        resetWizard();

        ScrollView sv = new ScrollView(sAct);
        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        root.setBackgroundColor(surface());

        // 品牌头部
        LinearLayout brand = new LinearLayout(sAct);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(0, 0, 0, dp(6));

        TextView rocket = new TextView(sAct);
        rocket.setText("🚀");
        rocket.setTextSize(28);
        brand.addView(rocket);

        LinearLayout brandText = new LinearLayout(sAct);
        brandText.setOrientation(LinearLayout.VERTICAL);
        brandText.setPadding(dp(12), 0, 0, 0);

        TextView brandTitle = new TextView(sAct);
        brandTitle.setText("乐少万群定时群发");
        brandTitle.setTextSize(20);
        brandTitle.setTextColor(textPri());
        brandTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        brandText.addView(brandTitle);

        TextView brandSub = new TextView(sAct);
        brandSub.setText("选择消息类型，配置内容，一键群发");
        brandSub.setTextSize(12);
        brandSub.setTextColor(textSec());
        brandSub.setPadding(0, dp(2), 0, 0);
        brandText.addView(brandSub);
        brand.addView(brandText);
        root.addView(brand);

        // 分隔线
        View sep = new View(sAct);
        sep.setBackgroundColor(dividerCol());
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        sepLp.setMargins(0, dp(16), 0, dp(16));
        root.addView(sep, sepLp);

        // 类型选择网格：2列自适应
        int screenW = sAct.getResources().getDisplayMetrics().widthPixels - dp(48) - dp(10);
        int cardW = screenW / 2;

        LinearLayout grid = new LinearLayout(sAct);
        grid.setOrientation(LinearLayout.VERTICAL);

        int[][] rows = {{0, 1}, {2, 3}, {4, 5}};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(sAct);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, 0, 0, dp(10));

            for (int idx : rows[r]) {
                if (idx < 0) {
                    View spacer = new View(sAct);
                    row.addView(spacer, new LinearLayout.LayoutParams(cardW, 1));
                    continue;
                }

                LinearLayout card = new LinearLayout(sAct);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);

                int col = neonColor(idx);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(glassCard());
                cardBg.setCornerRadius(dp(20));
                cardBg.setStroke(dp(2), (col & 0x00FFFFFF) | 0x30000000);
                card.setBackground(cardBg);
                card.setElevation(dp(2));
                card.setPadding(dp(14), dp(20), dp(14), dp(14));

                // 图标区
                FrameLayout iconWrap = new FrameLayout(sAct);
                int iconSize = dp(52);
                GradientDrawable iconGlow = new GradientDrawable();
                iconGlow.setColor(neonColorDim(idx));
                iconGlow.setCornerRadius(dp(18));
                iconWrap.setBackground(iconGlow);

                TextView icon = new TextView(sAct);
                icon.setText(MASS_TYPE_ICONS[idx]);
                icon.setTextSize(24);
                icon.setGravity(Gravity.CENTER);
                iconWrap.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize));
                card.addView(iconWrap);

                // 名称
                TextView name = new TextView(sAct);
                name.setText(MASS_TYPES[idx]);
                name.setTextSize(12);
                name.setTextColor(textPri());
                name.setTypeface(null, android.graphics.Typeface.BOLD);
                name.setGravity(Gravity.CENTER);
                name.setPadding(0, dp(10), 0, dp(2));
                card.addView(name);

                // 描述
                TextView desc = new TextView(sAct);
                desc.setText(MASS_TYPE_DESCS[idx]);
                desc.setTextSize(10);
                desc.setTextColor(textSec());
                desc.setGravity(Gravity.CENTER);
                desc.setPadding(dp(4), 0, dp(4), 0);
                card.addView(desc);

                final int fIdx = idx;
                card.setOnClickListener(v -> {
                    sWizardType = fIdx;
                    showMassSendContent();
                });

                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cardW, dp(130));
                clp.setMargins(dp(5), 0, dp(5), 0);
                card.setLayoutParams(clp);
                row.addView(card);
            }
            grid.addView(row);
        }
        root.addView(grid);

        // 底部按钮
        View sep2 = new View(sAct);
        sep2.setBackgroundColor(dividerCol());
        LinearLayout.LayoutParams sep2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        sep2Lp.setMargins(0, dp(16), 0, dp(16));
        root.addView(sep2, sep2Lp);

        LinearLayout bottom = new LinearLayout(sAct);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);

        Button recBtn = makeNeonOutlineBtn("查看记录", NEON_CYAN);
        bottom.addView(recBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        View bSpacer = new View(sAct);
        bottom.addView(bSpacer, new LinearLayout.LayoutParams(dp(12), 1));

        Button cancelBtn = makeGlassTextBtn("取消", textDim());
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        bottom.addView(cancelBtn, cbLp);
        root.addView(bottom);

        sv.addView(root);

        final Dialog dlg = new Dialog(sAct);
        dlg.setContentView(sv);
        dlg.setCanceledOnTouchOutside(true);

        recBtn.setOnClickListener(v -> { dlg.dismiss(); showMassSendRecords(); });
        cancelBtn.setOnClickListener(v -> dlg.dismiss());

        Window w = dlg.getWindow();
        if (w != null) {
            int ww = (int) (sAct.getResources().getDisplayMetrics().widthPixels * 0.92f);
            w.setLayout(ww, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setBackgroundDrawable(new GradientDrawable() {{
                setColor(surface());
                setCornerRadius(dp(20));
            }});
            w.getAttributes().dimAmount = 0.5f;
        }
        dlg.show();
    }

    // ==================== Step 2: 内容配置 ====================

    static void showMassSendContent() {
        if (sAct == null || sAct.isFinishing()) return;

        ScrollView sv = new ScrollView(sAct);
        sv.setFillViewport(true);

        LinearLayout wrapper = new LinearLayout(sAct);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(surface());

        LinearLayout inner = new LinearLayout(sAct);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(20), dp(20), dp(20), dp(0));

        // 步骤指示器
        inner.setTag(1);
        addStepIndicator(inner, 3);

        // 标题行
        LinearLayout headRow = new LinearLayout(sAct);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(0, 0, 0, dp(20));

        int headColor = neonColor(sWizardType);
        FrameLayout iconWrap = new FrameLayout(sAct);
        int hiSize = dp(44);
        GradientDrawable hiGlow = new GradientDrawable();
        hiGlow.setColor(neonColorDim(sWizardType));
        hiGlow.setCornerRadius(dp(14));
        iconWrap.setBackground(hiGlow);

        TextView headIcon = new TextView(sAct);
        headIcon.setText(MASS_TYPE_ICONS[sWizardType]);
        headIcon.setTextSize(20);
        headIcon.setGravity(Gravity.CENTER);
        iconWrap.addView(headIcon, new FrameLayout.LayoutParams(hiSize, hiSize));
        headRow.addView(iconWrap);

        TextView headTitle = new TextView(sAct);
        headTitle.setText("  " + MASS_TYPES[sWizardType]);
        headTitle.setTextSize(17);
        headTitle.setTextColor(textPri());
        headTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        headRow.addView(headTitle);
        inner.addView(headRow);

        String typeKey = MASS_TYPE_KEYS[sWizardType];
        boolean showText = "text".equals(typeKey) || typeKey.contains("text") || typeKey.contains("mixed");

        // === 文字输入 ===
        if (showText) {
            LinearLayout card = makeNeonCard(headColor);
            LinearLayout cardInner = new LinearLayout(sAct);
            cardInner.setOrientation(LinearLayout.VERTICAL);

            TextView label = new TextView(sAct);
            label.setText("文字内容");
            label.setTextSize(12);
            label.setTextColor(textSec());
            label.setPadding(0, 0, 0, dp(8));
            cardInner.addView(label);

            final EditText et = new EditText(sAct);
            et.setHint("text".equals(typeKey) ? "输入群发文字消息..." : "输入文字说明（可选）...");
            et.setMinLines(3);
            et.setMaxLines(8);
            et.setPadding(dp(14), dp(14), dp(14), dp(14));
            et.setTextColor(textPri());
            et.setHintTextColor(textDim());
            et.setBackground(makeGlassInputBg());
            et.setTag("editText");
            et.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) sWizardText = et.getText().toString().trim();
            });
            cardInner.addView(et);
            card.addView(cardInner);
            inner.addView(card);
        }

        // === 图片选择 ===
        if (typeKey.contains("image")) {
            LinearLayout imgCard = makeNeonCard(headColor);
            imgCard.setPadding(dp(16), dp(14), dp(16), dp(14));

            LinearLayout imgHead = new LinearLayout(sAct);
            imgHead.setOrientation(LinearLayout.HORIZONTAL);
            imgHead.setGravity(Gravity.CENTER_VERTICAL);

            TextView imgLabel = new TextView(sAct);
            imgLabel.setText("图片 · " + sWizardImagePaths.size() + " 张已选");
            imgLabel.setTextSize(12);
            imgLabel.setTextColor(textSec());
            imgHead.addView(imgLabel, new LinearLayout.LayoutParams(0, dp(36), 1f));

            Button addImgBtn = makeNeonOutlineBtn("+ 添加", NEON_CYAN);
            LinearLayout.LayoutParams aibLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
            aibLp.setMargins(0, 0, dp(8), 0);
            addImgBtn.setLayoutParams(aibLp);
            addImgBtn.setTextSize(11);
            imgHead.addView(addImgBtn);

            Button clrImgBtn = new Button(sAct);
            clrImgBtn.setText("清空");
            clrImgBtn.setTextSize(11);
            clrImgBtn.setAllCaps(false);
            clrImgBtn.setTextColor(NEON_RED);
            clrImgBtn.setBackgroundColor(0x00000000);
            clrImgBtn.setPadding(dp(8), 0, dp(8), 0);
            imgHead.addView(clrImgBtn);
            imgCard.addView(imgHead);

            final LinearLayout imgGrid = new LinearLayout(sAct);
            imgGrid.setOrientation(LinearLayout.VERTICAL);
            imgGrid.setPadding(0, dp(10), 0, 0);
            imgCard.addView(imgGrid);

            final Runnable[] refreshGridHolder = new Runnable[1];

            Runnable refreshGrid = new Runnable() {
                @Override public void run() {
                    imgGrid.removeAllViews();
                    final int size = sWizardImagePaths.size();
                    final int cols = 4;
                    final int thSize = dp(72);
                    int borderCol = glassBorder();
                    for (int r = 0; r < (size + cols) / cols; r++) {
                        LinearLayout row = new LinearLayout(sAct);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        for (int c = 0; c < cols; c++) {
                            int idx = r * cols + c;
                            if (idx < size) {
                                FrameLayout cell = new FrameLayout(sAct);
                                GradientDrawable cellBg = new GradientDrawable();
                                cellBg.setCornerRadius(dp(12));
                                cellBg.setStroke(dp(1), borderCol);
                                cell.setBackground(cellBg);
                                cell.setClipToOutline(true);

                                ImageView iv = new ImageView(sAct);
                                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                try {
                                    BitmapFactory.Options opts = new BitmapFactory.Options();
                                    opts.inSampleSize = 4;
                                    Bitmap bmp = BitmapFactory.decodeFile(sWizardImagePaths.get(idx), opts);
                                    if (bmp != null) {
                                        iv.setImageBitmap(bmp);
                                    }
                                } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }

                                cell.addView(iv, new FrameLayout.LayoutParams(thSize, thSize));

                                // 删除标识角标
                                TextView badge = new TextView(sAct);
                                badge.setText("✕");
                                badge.setTextSize(8);
                                badge.setTextColor(0xFFFFFFFF);
                                badge.setGravity(Gravity.CENTER);
                                GradientDrawable badgeBg = new GradientDrawable();
                                badgeBg.setColor(NEON_RED);
                                badgeBg.setCornerRadius(dp(10));
                                badge.setBackground(badgeBg);
                                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(18), dp(18));
                                blp.gravity = Gravity.TOP | Gravity.END;
                                blp.setMargins(0, dp(2), dp(2), 0);
                                cell.addView(badge, blp);

                                final int delIdx = idx;
                                cell.setOnClickListener(v -> {
                                    sWizardImagePaths.remove(delIdx);
                                    refreshGridHolder[0].run();
                                });

                                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(thSize, thSize);
                                clp.setMargins(dp(4), dp(4), dp(4), dp(4));
                                cell.setLayoutParams(clp);
                                row.addView(cell);
                            } else if (idx == size) {
                                FrameLayout addCell = new FrameLayout(sAct);
                                GradientDrawable addBg = new GradientDrawable();
                                addBg.setStroke(dp(2), (NEON_CYAN & 0x00FFFFFF) | 0x50000000);
                                addBg.setCornerRadius(dp(12));
                                addBg.setColor(0x00000000);
                                addCell.setBackground(addBg);

                                TextView addBtn = new TextView(sAct);
                                addBtn.setText("+");
                                addBtn.setTextSize(22);
                                addBtn.setTextColor(NEON_CYAN);
                                addBtn.setTypeface(null, android.graphics.Typeface.BOLD);
                                addBtn.setGravity(Gravity.CENTER);
                                addCell.addView(addBtn, new FrameLayout.LayoutParams(thSize, thSize));

                                addCell.setOnClickListener(v -> {
                                    pickImage(path -> {
                                        if (path != null) {
                                            sWizardImagePaths.add(path);
                                            refreshGridHolder[0].run();
                                        }
                                    });
                                });

                                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(thSize, thSize);
                                clp.setMargins(dp(4), dp(4), dp(4), dp(4));
                                addCell.setLayoutParams(clp);
                                row.addView(addCell);
                            }
                        }
                        imgGrid.addView(row);
                    }
                    if (size == 0) {
                        TextView hint = new TextView(sAct);
                        hint.setText("点击 + 选择图片");
                        hint.setTextSize(12);
                        hint.setTextColor(textDim());
                        hint.setGravity(Gravity.CENTER);
                        hint.setPadding(0, dp(14), 0, dp(4));
                        imgGrid.addView(hint);
                    }
                }
            };
            refreshGridHolder[0] = refreshGrid;

            addImgBtn.setOnClickListener(v -> {
                pickImage(path -> {
                    if (path != null) {
                        sWizardImagePaths.add(path);
                        refreshGridHolder[0].run();
                    }
                });
            });
            clrImgBtn.setOnClickListener(v -> { sWizardImagePaths.clear(); refreshGridHolder[0].run(); });

            refreshGridHolder[0].run();
            inner.addView(imgCard);
        }

        // === 视频选择 ===
        if (typeKey.startsWith("video") || typeKey.equals("video")) {
            LinearLayout vidCard = makeGlassCard();
            LinearLayout vidInner = new LinearLayout(sAct);
            vidInner.setOrientation(LinearLayout.VERTICAL);

            TextView vidLabel = new TextView(sAct);
            vidLabel.setText("视频文件");
            vidLabel.setTextSize(12);
            vidLabel.setTextColor(textSec());
            vidLabel.setPadding(0, 0, 0, dp(8));
            vidInner.addView(vidLabel);

            LinearLayout vidRow = new LinearLayout(sAct);
            vidRow.setOrientation(LinearLayout.HORIZONTAL);
            vidRow.setGravity(Gravity.CENTER_VERTICAL);

            final TextView vidPreview = new TextView(sAct);
            vidPreview.setText(sWizardVideoPath != null ? new File(sWizardVideoPath).getName() : "未选择视频");
            vidPreview.setTextSize(12);
            vidPreview.setTextColor(sWizardVideoPath != null ? NEON_PURPLE : textDim());
            vidRow.addView(vidPreview, new LinearLayout.LayoutParams(0, dp(36), 1f));

            Button vidBtn = makeNeonOutlineBtn("选择文件", NEON_PURPLE);
            vidBtn.setOnClickListener(v -> launchSystemFilePicker("video/*", path -> {
                sWizardVideoPath = path;
                vidPreview.setText(path != null ? new File(path).getName() : "未选择视频");
                vidPreview.setTextColor(path != null ? NEON_PURPLE : textDim());
            }));
            vidRow.addView(vidBtn);
            vidInner.addView(vidRow);
            vidCard.addView(vidInner);
            inner.addView(vidCard);
        }

        // === 语音选择 ===
        if (typeKey.equals("voice")) {
            LinearLayout audCard = makeGlassCard();
            LinearLayout audInner = new LinearLayout(sAct);
            audInner.setOrientation(LinearLayout.VERTICAL);

            TextView audLabel = new TextView(sAct);
            audLabel.setText("语音文件");
            audLabel.setTextSize(12);
            audLabel.setTextColor(textSec());
            audLabel.setPadding(0, 0, 0, dp(8));
            audInner.addView(audLabel);

            LinearLayout audRow = new LinearLayout(sAct);
            audRow.setOrientation(LinearLayout.HORIZONTAL);
            audRow.setGravity(Gravity.CENTER_VERTICAL);

            final TextView audPreview = new TextView(sAct);
            audPreview.setText(sWizardAudioPath != null ? new File(sWizardAudioPath).getName() : "未选择音频");
            audPreview.setTextSize(12);
            audPreview.setTextColor(sWizardAudioPath != null ? NEON_ORANGE : textDim());
            audRow.addView(audPreview, new LinearLayout.LayoutParams(0, dp(36), 1f));

            Button audBtn = makeNeonOutlineBtn("选择文件", NEON_ORANGE);
            audBtn.setOnClickListener(v -> pickAudio(path -> {
                sWizardAudioPath = path;
                audPreview.setText(path != null ? new File(path).getName() : "未选择音频");
                audPreview.setTextColor(path != null ? NEON_ORANGE : textDim());
            }));
            audRow.addView(audBtn);
            audInner.addView(audRow);
            audCard.addView(audInner);
            inner.addView(audCard);
        }

        // === 发送时间设置 ===
        LinearLayout timeCard = makeGlassCard();
        LinearLayout tInner = new LinearLayout(sAct);
        tInner.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tHead = new LinearLayout(sAct);
        tHead.setOrientation(LinearLayout.HORIZONTAL);
        tHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView tLabel = new TextView(sAct);
        tLabel.setText("发送时间");
        tLabel.setTextSize(12);
        tLabel.setTextColor(textSec());
        tHead.addView(tLabel, new LinearLayout.LayoutParams(0, dp(36), 1f));

        final TextView timeVal = new TextView(sAct);
        timeVal.setText("未设置");
        timeVal.setTextSize(13);
        timeVal.setTextColor(sWizardTimeMs > 0 ? NEON_PINK : textDim());
        timeVal.setPadding(0, 0, dp(12), 0);
        tHead.addView(timeVal);

        Button timeBtn = makeNeonOutlineBtn("选择", NEON_PINK);
        tHead.addView(timeBtn);
        tInner.addView(tHead);
        timeCard.addView(tInner);
        inner.addView(timeCard);

        timeBtn.setOnClickListener(v -> showGlassDateTimePicker(dateTimeMs -> {
            if (dateTimeMs != null) {
                sWizardTimeMs = dateTimeMs;
                SimpleDateFormat fmt = new SimpleDateFormat("MM月dd日 HH:mm");
                timeVal.setText(fmt.format(new Date(sWizardTimeMs)));
                timeVal.setTextColor(NEON_PINK);
            }
        }));

        // === 随机延迟开关 ===
        LinearLayout delayRow = new LinearLayout(sAct);
        delayRow.setOrientation(LinearLayout.HORIZONTAL);
        delayRow.setGravity(Gravity.CENTER_VERTICAL);
        delayRow.setPadding(0, dp(16), 0, dp(0));

        LinearLayout dlText = new LinearLayout(sAct);
        dlText.setOrientation(LinearLayout.VERTICAL);
        TextView dlt = new TextView(sAct);
        dlt.setText("随机延迟发送");
        dlt.setTextSize(13);
        dlt.setTextColor(textPri());
        dlText.addView(dlt);
        TextView dls = new TextView(sAct);
        dls.setText("每个目标之间随机 1-10 秒");
        dls.setTextSize(10);
        dls.setTextColor(textSec());
        dlText.addView(dls);
        delayRow.addView(dlText, new LinearLayout.LayoutParams(0, dp(40), 1f));

        Switch delaySw = CandyUi.newSwitch(sAct);
        delaySw.setChecked(sWizardDelay);
        delaySw.setOnCheckedChangeListener((btn, on) -> sWizardDelay = on);
        delayRow.addView(delaySw);
        inner.addView(delayRow);

        wrapper.addView(inner);
        sv.addView(wrapper);

        // 底部导航栏
        LinearLayout bottomBar = new LinearLayout(sAct);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(20), dp(16), dp(20), dp(20));
        bottomBar.setBackgroundColor(surface());

        Button backBtn = makeGlassTextBtn("← 上一步", textSec());
        bottomBar.addView(backBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button nextBtn = makeNeonBtn("下一步 →", headColor);
        bottomBar.addView(nextBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        LinearLayout step2Wrap = new LinearLayout(sAct);
        step2Wrap.setOrientation(LinearLayout.VERTICAL);
        step2Wrap.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        step2Wrap.addView(bottomBar);

        final Dialog dlgStep2 = new Dialog(sAct);
        dlgStep2.setContentView(step2Wrap);
        dlgStep2.setCanceledOnTouchOutside(true);

        backBtn.setOnClickListener(v2 -> { dlgStep2.dismiss(); showMassSend(); });
        nextBtn.setOnClickListener(v2 -> {
            try {
                View found = step2Wrap.findViewWithTag("editText");
                if (found instanceof EditText) {
                    EditText et = (EditText) found;
                    sWizardText = et.getText().toString().trim();
                }
            } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }

            boolean hasText = !sWizardText.isEmpty();
            boolean hasAttachment = false;
            switch (typeKey) {
                case "image": case "image_text":
                    hasAttachment = !sWizardImagePaths.isEmpty(); break;
                case "video": case "video_text":
                    hasAttachment = sWizardVideoPath != null; break;
                case "voice":
                    hasAttachment = sWizardAudioPath != null; break;
            }

            if ("text".equals(typeKey) && !hasText) { toast("请输入文字内容"); return; }
            if (!"text".equals(typeKey) && !"voice".equals(typeKey) && !hasText && !hasAttachment) {
                toast("请至少输入文字或选择文件"); return;
            }
            if ("voice".equals(typeKey) && !hasAttachment) { toast("请选择语音文件"); return; }
            if (sWizardTimeMs <= 0) { toast("请设置发送时间"); return; }
            if (sWizardTimeMs <= System.currentTimeMillis()) { toast("时间必须在未来"); return; }

            dlgStep2.dismiss();
            showMassSendTarget();
        });

        Window w2 = dlgStep2.getWindow();
        if (w2 != null) {
            int ww = (int) (sAct.getResources().getDisplayMetrics().widthPixels * 0.92f);
            w2.setLayout(ww, WindowManager.LayoutParams.WRAP_CONTENT);
            w2.setGravity(Gravity.CENTER);
            w2.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            w2.getAttributes().dimAmount = 0.5f;
        }
        dlgStep2.show();
    }

    // ==================== Step 3: 目标确认 ====================

    static void showMassSendTarget() {
        if (sAct == null || sAct.isFinishing()) return;

        ScrollView sv = new ScrollView(sAct);
        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(0));
        root.setBackgroundColor(surface());

        // 步骤指示器
        addStepIndicator(root, 3);

        // 标题
        LinearLayout headRow = new LinearLayout(sAct);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(0, 0, 0, dp(20));

        int headColor = neonColor(sWizardType);
        FrameLayout hiWrap = new FrameLayout(sAct);
        int hiSize = dp(44);
        GradientDrawable hiGlow = new GradientDrawable();
        hiGlow.setColor(neonColorDim(sWizardType));
        hiGlow.setCornerRadius(dp(14));
        hiWrap.setBackground(hiGlow);
        TextView headIcon = new TextView(sAct);
        headIcon.setText("🎯");
        headIcon.setTextSize(20);
        headIcon.setGravity(Gravity.CENTER);
        hiWrap.addView(headIcon, new FrameLayout.LayoutParams(hiSize, hiSize));
        headRow.addView(hiWrap);

        TextView headTitle = new TextView(sAct);
        headTitle.setText("  确认并发送");
        headTitle.setTextSize(17);
        headTitle.setTextColor(textPri());
        headTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        headRow.addView(headTitle);
        root.addView(headRow);

        // 任务摘要卡片
        LinearLayout summaryCard = makeNeonCard(headColor);
        LinearLayout sInner = new LinearLayout(sAct);
        sInner.setOrientation(LinearLayout.VERTICAL);

        LinearLayout sRow = new LinearLayout(sAct);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        sRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView sIcon = new TextView(sAct);
        sIcon.setText(MASS_TYPE_ICONS[sWizardType]);
        sIcon.setTextSize(16);
        sIcon.setGravity(Gravity.CENTER);
        GradientDrawable sIconBg = new GradientDrawable();
        sIconBg.setColor(headColor);
        sIconBg.setCornerRadius(dp(10));
        sIcon.setBackground(sIconBg);
        sIcon.setPadding(dp(10), dp(8), dp(10), dp(8));
        sRow.addView(sIcon);

        TextView sType = new TextView(sAct);
        sType.setText("  " + MASS_TYPES[sWizardType]);
        sType.setTextSize(15);
        sType.setTextColor(textPri());
        sType.setTypeface(null, android.graphics.Typeface.BOLD);
        sRow.addView(sType);
        sInner.addView(sRow);

        if (!sWizardText.isEmpty()) {
            TextView stv = new TextView(sAct);
            stv.setText(sWizardText);
            stv.setTextSize(12);
            stv.setTextColor(textPri());
            stv.setPadding(dp(42), dp(8), 0, 0);
            sInner.addView(stv);
        }

        if (!sWizardImagePaths.isEmpty()) {
            TextView siv = new TextView(sAct);
            siv.setText("图片 · " + sWizardImagePaths.size() + " 张");
            siv.setTextSize(12);
            siv.setTextColor(textSec());
            siv.setPadding(dp(42), dp(8), 0, dp(4));
            sInner.addView(siv);

            LinearLayout thumbRow = new LinearLayout(sAct);
            thumbRow.setOrientation(LinearLayout.HORIZONTAL);
            thumbRow.setPadding(dp(42), dp(4), 0, 0);
            int thSize = dp(60);
            int maxShow = Math.min(sWizardImagePaths.size(), 4);
            for (int i = 0; i < maxShow; i++) {
                FrameLayout thCell = new FrameLayout(sAct);
                GradientDrawable thBg = new GradientDrawable();
                thBg.setCornerRadius(dp(8));
                thBg.setStroke(dp(1), glassBorder());
                thCell.setBackground(thBg);
                thCell.setClipToOutline(true);

                ImageView iv = new ImageView(sAct);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    Bitmap bmp = BitmapFactory.decodeFile(sWizardImagePaths.get(i), opts);
                    if (bmp != null) iv.setImageBitmap(bmp);
                } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
                thCell.addView(iv, new FrameLayout.LayoutParams(thSize, thSize));

                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(thSize, thSize);
                tlp.setMargins(0, 0, dp(4), 0);
                thCell.setLayoutParams(tlp);
                thumbRow.addView(thCell);
            }
            if (sWizardImagePaths.size() > 4) {
                TextView more = new TextView(sAct);
                more.setText("+" + (sWizardImagePaths.size() - 4));
                more.setTextSize(13);
                more.setTextColor(textSec());
                more.setGravity(Gravity.CENTER);
                more.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(48), thSize);
                mlp.gravity = Gravity.CENTER_VERTICAL;
                more.setLayoutParams(mlp);
                thumbRow.addView(more);
            }
            sInner.addView(thumbRow);
        }
        if (sWizardVideoPath != null) {
            TextView svv = new TextView(sAct);
            svv.setText("📹 " + new File(sWizardVideoPath).getName());
            svv.setTextSize(11);
            svv.setTextColor(textSec());
            svv.setPadding(dp(42), dp(4), 0, 0);
            sInner.addView(svv);
        }
        if (sWizardAudioPath != null) {
            TextView sav = new TextView(sAct);
            sav.setText("🎵 " + new File(sWizardAudioPath).getName());
            sav.setTextSize(11);
            sav.setTextColor(textSec());
            sav.setPadding(dp(42), dp(4), 0, 0);
            sInner.addView(sav);
        }

        SimpleDateFormat fmt = new SimpleDateFormat("MM月dd日 HH:mm");
        TextView stime = new TextView(sAct);
        stime.setText("⏰ " + fmt.format(new Date(sWizardTimeMs)));
        stime.setTextSize(12);
        stime.setTextColor(NEON_ORANGE);
        stime.setTypeface(null, android.graphics.Typeface.BOLD);
        stime.setPadding(dp(42), dp(8), 0, 0);
        sInner.addView(stime);

        if (sWizardDelay) {
            TextView sd = new TextView(sAct);
            sd.setText("⚡ 已开启随机延迟 1-10 秒");
            sd.setTextSize(11);
            sd.setTextColor(NEON_CYAN);
            sd.setPadding(dp(42), dp(4), 0, 0);
            sInner.addView(sd);
        }
        summaryCard.addView(sInner);
        root.addView(summaryCard);

        // 目标选择
        LinearLayout targetCard = makeGlassCard();
        LinearLayout tcInner = new LinearLayout(sAct);
        tcInner.setOrientation(LinearLayout.VERTICAL);

        TextView targetTitle = new TextView(sAct);
        targetTitle.setText("发送目标");
        targetTitle.setTextSize(12);
        targetTitle.setTextColor(textSec());
        targetTitle.setPadding(0, 0, 0, dp(8));
        tcInner.addView(targetTitle);

        LinearLayout tRow = new LinearLayout(sAct);
        tRow.setOrientation(LinearLayout.HORIZONTAL);
        tRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView targetCount = new TextView(sAct);
        targetCount.setText(sWizardTargets.isEmpty() ? "未选择群聊" : "已选择 " + sWizardTargets.size() + " 个群聊");
        targetCount.setTextSize(14);
        targetCount.setTextColor(sWizardTargets.isEmpty() ? textDim() : NEON_PINK);
        targetCount.setTypeface(null, android.graphics.Typeface.BOLD);
        tRow.addView(targetCount, new LinearLayout.LayoutParams(0, dp(40), 1f));

        Button pickTargetBtn = makeNeonOutlineBtn("选择群聊", NEON_CYAN);
        tRow.addView(pickTargetBtn);
        tcInner.addView(tRow);
        targetCard.addView(tcInner);
        root.addView(targetCard);

        pickTargetBtn.setOnClickListener(v -> {
            com.leshao.v3.ui.ContactPickerDialog.show(sAct, "",
                    com.leshao.v3.ui.ContactPickerDialog.MODE_GROUP,
                    (selected, display) -> {
                        if (selected.isEmpty()) return;
                        sWizardTargets.clear();
                        sWizardTargets.addAll(selected);
                        targetCount.setText("已选择 " + sWizardTargets.size() + " 个群聊");
                        targetCount.setTextColor(NEON_PINK);
                    });
        });

        // 目标预览
        if (!sWizardTargets.isEmpty()) {
            LinearLayout previewCard = makeGlassCard();
            LinearLayout pcInner = new LinearLayout(sAct);
            pcInner.setOrientation(LinearLayout.VERTICAL);

            int shown = Math.min(sWizardTargets.size(), 5);
            int i = 0;
            for (String t : sWizardTargets) {
                if (i++ >= shown) break;
                TextView tv = new TextView(sAct);
                tv.setText(t);
                tv.setTextSize(11);
                tv.setTextColor(textSec());
                tv.setPadding(0, dp(4), 0, 0);
                pcInner.addView(tv);
            }
            if (sWizardTargets.size() > 5) {
                TextView more = new TextView(sAct);
                more.setText("... 还有 " + (sWizardTargets.size() - 5) + " 个群聊");
                more.setTextSize(11);
                more.setTextColor(textDim());
                more.setPadding(0, dp(4), 0, 0);
                pcInner.addView(more);
            }
            previewCard.addView(pcInner);
            root.addView(previewCard);
        }

        // 确认信息
        LinearLayout infoCard = makeGlassCard();
        LinearLayout infoInner = new LinearLayout(sAct);
        infoInner.setOrientation(LinearLayout.VERTICAL);

        TextView inf = new TextView(sAct);
        SimpleDateFormat fmt2 = new SimpleDateFormat("MM月dd日 HH:mm");
        String taskName = taskNameByType(sWizardType);
        inf.setText("任务将在 " + fmt2.format(new Date(sWizardTimeMs)) + " 准时执行\n如遇问题请联系乐少排查");
        inf.setTextSize(12);
        inf.setTextColor(textSec());
        inf.setLineSpacing(dp(4), 1f);
        infoInner.addView(inf);
        infoCard.addView(infoInner);
        root.addView(infoCard);

        // 底部按钮
        LinearLayout bottomBar = new LinearLayout(sAct);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(0, dp(20), 0, dp(0));
        bottomBar.setBackgroundColor(surface());

        Button backBtn = makeGlassTextBtn("← 上一步", textSec());
        bottomBar.addView(backBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button confirmBtn = makeNeonBtn("确认发送", headColor);
        bottomBar.addView(confirmBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(bottomBar);

        backBtn.setOnClickListener(v2 -> { showMassSendContent(); });
        confirmBtn.setOnClickListener(v2 -> {
            if (sWizardTargets.isEmpty()) { toast("请选择目标群聊"); return; }

            // 持久化向导数据
            try {
                WmPrefs.setStr("mass_send_type", MASS_TYPE_KEYS[sWizardType]);
                WmPrefs.setStr("mass_send_text", sWizardText);
                WmPrefs.setStr("mass_send_targets", new JSONArray(new ArrayList<>(sWizardTargets)).toString());
                if (!sWizardImagePaths.isEmpty()) {
                    WmPrefs.setStr("mass_send_images", new JSONArray(sWizardImagePaths).toString());
                } else {
                    WmPrefs.setStr("mass_send_images", "");
                }
                if (sWizardVideoPath != null) WmPrefs.setStr("mass_send_video", sWizardVideoPath);
                else WmPrefs.setStr("mass_send_video", "");
                if (sWizardAudioPath != null) WmPrefs.setStr("mass_send_audio", sWizardAudioPath);
                else WmPrefs.setStr("mass_send_audio", "");
                WmPrefs.setStr("mass_send_task_id", "task_" + System.currentTimeMillis());
                WmPrefs.set("mass_send_delay", sWizardDelay);
            } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }

            saveMassSendRecord(MASS_TYPES[sWizardType], sWizardTargets.size(), 0, 0);
            scheduleMassSend(sWizardTimeMs);

            // 关闭弹窗并提示
            if (root.getTag() instanceof Dialog) {
                ((Dialog) root.getTag()).dismiss();
            }
            toast("任务已创建，" + new SimpleDateFormat("MM月dd日 HH:mm").format(new Date(sWizardTimeMs)) + " 准时发送");
        });

        final Dialog dlgStep3 = new Dialog(sAct);
        dlgStep3.setContentView(root);
        dlgStep3.setCanceledOnTouchOutside(true);

        root.setTag(dlgStep3);

        Window w3 = dlgStep3.getWindow();
        if (w3 != null) {
            int ww = (int) (sAct.getResources().getDisplayMetrics().widthPixels * 0.92f);
            w3.setLayout(ww, WindowManager.LayoutParams.WRAP_CONTENT);
            w3.setGravity(Gravity.CENTER);
            w3.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            w3.getAttributes().dimAmount = 0.5f;
        }
        dlgStep3.show();
    }

    // ==================== 自定义日期时间选择器（适配浅色/暗色主题） ====================

    private interface DateTimeCallback { void onResult(Long timeMs); }

    private static void showGlassDateTimePicker(DateTimeCallback cb) {
        if (sAct == null || sAct.isFinishing()) return;

        final Calendar cal = Calendar.getInstance();
        if (sWizardTimeMs > 0) cal.setTimeInMillis(sWizardTimeMs);

        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(surface());

        // 标题
        TextView title = new TextView(sAct);
        title.setText("选择发送时间");
        title.setTextSize(17);
        title.setTextColor(textPri());
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(20));
        root.addView(title);

        // 日期选择器
        final NumberPicker yearPicker = new NumberPicker(sAct);
        yearPicker.setMinValue(cal.get(Calendar.YEAR));
        yearPicker.setMaxValue(cal.get(Calendar.YEAR) + 5);
        yearPicker.setValue(cal.get(Calendar.YEAR));
        yearPicker.setWrapSelectorWheel(false);

        final NumberPicker monthPicker = new NumberPicker(sAct);
        monthPicker.setMinValue(1);
        monthPicker.setMaxValue(12);
        monthPicker.setValue(cal.get(Calendar.MONTH) + 1);
        monthPicker.setFormatter(v -> String.format("%02d", v));

        final NumberPicker dayPicker = new NumberPicker(sAct);
        dayPicker.setMinValue(1);
        dayPicker.setMaxValue(31);
        dayPicker.setValue(cal.get(Calendar.DAY_OF_MONTH));
        dayPicker.setFormatter(v -> String.format("%02d", v));

        LinearLayout dateRow = new LinearLayout(sAct);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER);

        dateRow.addView(yearPicker, new LinearLayout.LayoutParams(0, dp(160), 1f));
        TextView d1 = new TextView(sAct); d1.setText("年"); d1.setTextSize(13); d1.setTextColor(textSec());
        d1.setPadding(dp(4), 0, dp(12), 0); dateRow.addView(d1);

        dateRow.addView(monthPicker, new LinearLayout.LayoutParams(0, dp(160), 1f));
        TextView d2 = new TextView(sAct); d2.setText("月"); d2.setTextSize(13); d2.setTextColor(textSec());
        d2.setPadding(dp(4), 0, dp(12), 0); dateRow.addView(d2);

        dateRow.addView(dayPicker, new LinearLayout.LayoutParams(0, dp(160), 1f));
        TextView d3 = new TextView(sAct); d3.setText("日"); d3.setTextSize(13); d3.setTextColor(textSec());
        d3.setPadding(dp(4), 0, 0, 0); dateRow.addView(d3);
        root.addView(dateRow);

        // 分隔线
        View sep = new View(sAct);
        sep.setBackgroundColor(dividerCol());
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        sepLp.setMargins(0, dp(12), 0, dp(12));
        root.addView(sep, sepLp);

        // 时间选择器
        final NumberPicker hourPicker = new NumberPicker(sAct);
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        hourPicker.setValue(cal.get(Calendar.HOUR_OF_DAY));
        hourPicker.setFormatter(v -> String.format("%02d", v));

        final NumberPicker minutePicker = new NumberPicker(sAct);
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);
        minutePicker.setValue(cal.get(Calendar.MINUTE));
        minutePicker.setFormatter(v -> String.format("%02d", v));

        LinearLayout timeRow = new LinearLayout(sAct);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER);

        timeRow.addView(hourPicker, new LinearLayout.LayoutParams(0, dp(160), 1f));
        TextView t1 = new TextView(sAct); t1.setText("时"); t1.setTextSize(13); t1.setTextColor(textSec());
        t1.setPadding(dp(12), 0, dp(12), 0); timeRow.addView(t1);

        timeRow.addView(minutePicker, new LinearLayout.LayoutParams(0, dp(160), 1f));
        TextView t2 = new TextView(sAct); t2.setText("分"); t2.setTextSize(13); t2.setTextColor(textSec());
        t2.setPadding(dp(12), 0, 0, 0); timeRow.addView(t2);
        root.addView(timeRow);

        // NumberPicker 颜色
        styleNumberPicker(yearPicker);
        styleNumberPicker(monthPicker);
        styleNumberPicker(dayPicker);
        styleNumberPicker(hourPicker);
        styleNumberPicker(minutePicker);

        // 按钮
        LinearLayout btnRow = new LinearLayout(sAct);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, dp(20), 0, 0);

        Button cancelBtn = makeGlassTextBtn("取消", textDim());
        btnRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button okBtn = makeNeonBtn("确定", NEON_PINK);
        btnRow.addView(okBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(btnRow);

        final Dialog dlg = new Dialog(sAct);
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);

        cancelBtn.setOnClickListener(v -> dlg.dismiss());
        okBtn.setOnClickListener(v -> {
            cal.set(Calendar.YEAR, yearPicker.getValue());
            cal.set(Calendar.MONTH, monthPicker.getValue() - 1);
            cal.set(Calendar.DAY_OF_MONTH, dayPicker.getValue());
            cal.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
            cal.set(Calendar.MINUTE, minutePicker.getValue());
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            dlg.dismiss();
            cb.onResult(cal.getTimeInMillis());
        });

        Window w = dlg.getWindow();
        if (w != null) {
            int ww = (int) (sAct.getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setLayout(ww, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setBackgroundDrawable(new GradientDrawable() {{
                setColor(surface());
                setCornerRadius(dp(20));
            }});
            w.getAttributes().dimAmount = 0.5f;
        }
        dlg.show();
    }

    private static void styleNumberPicker(NumberPicker np) {
        try {
            int count = np.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = np.getChildAt(i);
                if (child instanceof EditText) {
                    EditText et = (EditText) child;
                    et.setTextColor(textPri());
                    et.setTextSize(16);
                }
            }
        } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
    }

    // ==================== 群发记录 ====================

    static void saveMassSendRecord(String type, int targetCount, int success, int fail) {
        saveMassSendRecord(type, targetCount, success, fail, "task_" + System.currentTimeMillis());
    }

    static void saveMassSendRecord(String type, int targetCount, int success, int fail, String taskId) {
        try {
            JSONArray arr;
            String existing = WmPrefs.getStr("mass_send_records", "");
            if (existing.isEmpty()) arr = new JSONArray();
            else arr = new JSONArray(existing);
            JSONObject obj = new JSONObject();
            obj.put("taskId", taskId);
            obj.put("time", System.currentTimeMillis());
            obj.put("scheduledTime", sWizardTimeMs > 0 ? sWizardTimeMs : System.currentTimeMillis());
            obj.put("type", type);
            obj.put("targetCount", targetCount);
            obj.put("success", success);
            obj.put("fail", fail);
            obj.put("status", "pending");
            obj.put("textPreview", sWizardText.length() > 30 ? sWizardText.substring(0, 30) + "..." : sWizardText);
            arr.put(obj);
            WmPrefs.setStr("mass_send_records", arr.toString());
        } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
    }

    static void saveMassSendFailRecord(String target, String type, String reason) {
        try {
            JSONArray arr;
            String existing = WmPrefs.getStr("mass_send_fail_records", "");
            if (existing.isEmpty()) arr = new JSONArray();
            else arr = new JSONArray(existing);
            JSONObject obj = new JSONObject();
            obj.put("time", System.currentTimeMillis());
            obj.put("target", target);
            obj.put("type", type);
            obj.put("reason", reason);
            arr.put(obj);
            WmPrefs.setStr("mass_send_fail_records", arr.toString());
        } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
    }

    static void showMassSendRecords() {
        if (sAct == null || sAct.isFinishing()) return;

        ScrollView sv = new ScrollView(sAct);
        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(surface());

        // 标题
        TextView title = new TextView(sAct);
        title.setText("群发记录");
        title.setTextSize(18);
        title.setTextColor(textPri());
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(20));
        root.addView(title);

        String existing = WmPrefs.getStr("mass_send_records", "");

        if (existing.isEmpty()) {
            LinearLayout emptyCard = makeGlassCard();
            LinearLayout ecInner = new LinearLayout(sAct);
            ecInner.setOrientation(LinearLayout.VERTICAL);
            ecInner.setGravity(Gravity.CENTER);
            ecInner.setPadding(0, dp(20), 0, dp(20));

            TextView eIcon = new TextView(sAct);
            eIcon.setText("📭");
            eIcon.setTextSize(40);
            eIcon.setGravity(Gravity.CENTER);
            ecInner.addView(eIcon);

            TextView eText = new TextView(sAct);
            eText.setText("暂无群发记录");
            eText.setTextSize(14);
            eText.setTextColor(textSec());
            eText.setGravity(Gravity.CENTER);
            eText.setPadding(0, dp(12), 0, 0);
            ecInner.addView(eText);
            emptyCard.addView(ecInner);
            root.addView(emptyCard);
        } else {
            try {
                JSONArray arr = new JSONArray(existing);
                for (int i = arr.length() - 1; i >= 0; i--) {
                    JSONObject obj = arr.getJSONObject(i);
                    String status = obj.optString("status", "pending");
                    String type = obj.optString("type", "");
                    int success = obj.optInt("success", 0);
                    int fail = obj.optInt("fail", 0);
                    int targetCount = obj.optInt("targetCount", 0);
                    String taskId = obj.optString("taskId", "");
                    String textPreview = obj.optString("textPreview", "");
                    long t = obj.optLong("scheduledTime", obj.optLong("time", 0));

                    LinearLayout card = makeGlassCard();

                    // 头行
                    LinearLayout topRow = new LinearLayout(sAct);
                    topRow.setOrientation(LinearLayout.HORIZONTAL);
                    topRow.setGravity(Gravity.CENTER_VERTICAL);

                    SimpleDateFormat fmt = new SimpleDateFormat("MM月dd日 HH:mm");
                    TextView timeTv = new TextView(sAct);
                    timeTv.setText(fmt.format(new Date(t)));
                    timeTv.setTextSize(12);
                    timeTv.setTextColor(textPri());
                    timeTv.setTypeface(null, android.graphics.Typeface.BOLD);
                    topRow.addView(timeTv);

                    TextView typeTv = new TextView(sAct);
                    typeTv.setText("  " + type);
                    typeTv.setTextSize(11);
                    typeTv.setTextColor(NEON_CYAN);
                    topRow.addView(typeTv);

                    // 状态标签
                    int stColor;
                    String stText;
                    switch (status) {
                        case "completed":
                            stColor = NEON_GREEN; stText = "已完成"; break;
                        case "running":
                            stColor = NEON_ORANGE; stText = "执行中"; break;
                        case "cancelled":
                            stColor = textDim(); stText = "已取消"; break;
                        default:
                            stColor = NEON_BLUE; stText = "等待中"; break;
                    }
                    TextView stTag = new TextView(sAct);
                    stTag.setText(stText);
                    stTag.setTextSize(9);
                    stTag.setTextColor(0xFFFFFFFF);
                    GradientDrawable stBg = new GradientDrawable();
                    stBg.setColor(stColor);
                    stBg.setCornerRadius(dp(8));
                    stTag.setBackground(stBg);
                    stTag.setPadding(dp(8), dp(2), dp(8), dp(2));

                    topRow.addView(new View(sAct), new LinearLayout.LayoutParams(0, 1, 1f));
                    topRow.addView(stTag);

                    card.addView(topRow);

                    // 中间行
                    LinearLayout midRow = new LinearLayout(sAct);
                    midRow.setOrientation(LinearLayout.HORIZONTAL);
                    midRow.setGravity(Gravity.CENTER_VERTICAL);
                    midRow.setPadding(0, dp(8), 0, dp(2));

                    TextView countTv = new TextView(sAct);
                    countTv.setText("目标: " + targetCount + " 群");
                    countTv.setTextSize(11);
                    countTv.setTextColor(textSec());
                    midRow.addView(countTv);

                    midRow.addView(new View(sAct), new LinearLayout.LayoutParams(0, 1, 1f));

                    if (!status.equals("pending")) {
                        TextView resTv = new TextView(sAct);
                        resTv.setText("成功 " + success + " / 失败 " + fail);
                        resTv.setTextSize(11);
                        resTv.setTextColor(fail > 0 ? NEON_RED : NEON_GREEN);
                        midRow.addView(resTv);
                    }
                    card.addView(midRow);

                    // 预览文字
                    if (!textPreview.isEmpty()) {
                        TextView prevTv = new TextView(sAct);
                        prevTv.setText(textPreview);
                        prevTv.setTextSize(10);
                        prevTv.setTextColor(textDim());
                        prevTv.setPadding(0, dp(4), 0, 0);
                        card.addView(prevTv);
                    }

                    root.addView(card);
                }
            } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
        }

        // 底部按钮
        LinearLayout bottom = new LinearLayout(sAct);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(0, dp(20), 0, 0);

        Button clearBtn = makeNeonOutlineBtn("清空记录", NEON_RED);
        bottom.addView(clearBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        View spacer = new View(sAct);
        bottom.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));

        Button closeBtn = makeNeonBtn("关闭", NEON_BLUE);
        bottom.addView(closeBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(bottom);

        sv.addView(root);

        final Dialog dlg = new Dialog(sAct);
        dlg.setContentView(sv);
        dlg.setCanceledOnTouchOutside(true);

        clearBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(sAct)
                .setTitle("清空记录")
                .setMessage("确定要清空所有群发记录吗？此操作不可撤销。")
                .setPositiveButton("确定", (d, w) -> {
                    WmPrefs.setStr("mass_send_records", "");
                    dlg.dismiss();
                    showMassSendRecords();
                })
                .setNegativeButton("取消", null)
                .show();
        });
        closeBtn.setOnClickListener(v -> dlg.dismiss());

        Window w = dlg.getWindow();
        if (w != null) {
            int ww = (int) (sAct.getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setLayout(ww, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setBackgroundDrawable(new GradientDrawable() {{
                setColor(surface());
                setCornerRadius(dp(20));
            }});
            w.getAttributes().dimAmount = 0.5f;
        }
        dlg.show();
    }

    private static String taskNameByType(int typeIdx) {
        switch (typeIdx) {
            case 0: return "文本群发";
            case 1: return "图片群发";
            case 2: return "视频群发";
            case 3: return "图文群发";
            case 4: return "文视群发";
            case 5: return "图文混合群发";
            case 6: return "语音群发";
            default: return "群发任务";
        }
    }

    // ==================== 调度引擎 ====================

    private static void scheduleMassSend(long triggerMs) {
        try {
            WmPrefs.setStr("mass_send_trigger_ms", String.valueOf(triggerMs));
            long delay = triggerMs - System.currentTimeMillis();
            if (delay < 0) delay = 0;
            LogWriter.log(TAG, "massSend scheduled: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(triggerMs)) + " delay=" + delay + "ms");
            if (sH != null) {
                sH.postDelayed(() -> {
                    LogWriter.log(TAG, "massSend handler triggered");
                    executeMassSendFromPrefs();
                }, delay);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "scheduleMassSend err: " + t.getMessage());
        }
    }

    private static void ensureReceiverRegistered() {
        if (sMassSendReceiver != null) return;
        Context ctx = (sCtx != null) ? sCtx : sAct;
        if (ctx == null) { LogWriter.log(TAG, "register receiver err: no context"); return; }
        try {
            sMassSendReceiver = new MassSendReceiver();
            IntentFilter filter = new IntentFilter("com.leshao.v3.MASS_SEND_TRIGGER");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(sMassSendReceiver, filter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(sMassSendReceiver, filter);
            }
            LogWriter.log(TAG, "massSend receiver registered dynamically");
        } catch (Exception e) {
            LogWriter.log(TAG, "register receiver err: " + e.getMessage());
        }
    }

    private static void recoverMassSendTask() {
        try {
            String type = WmPrefs.getStr("mass_send_type", "");
            if (type.isEmpty()) return;
            String triggerStr = WmPrefs.getStr("mass_send_trigger_ms", "");
            if (triggerStr.isEmpty()) return;
            long triggerMs = Long.parseLong(triggerStr);
            long now = System.currentTimeMillis();

            String taskId = WmPrefs.getStr("mass_send_task_id", "");
            String records = WmPrefs.getStr("mass_send_records", "");
            boolean isPending = false;
            if (!records.isEmpty() && !taskId.isEmpty()) {
                JSONArray arr = new JSONArray(records);
                for (int i = arr.length() - 1; i >= 0; i--) {
                    JSONObject obj = arr.getJSONObject(i);
                    if (taskId.equals(obj.optString("taskId", ""))
                            && "pending".equals(obj.optString("status", ""))) {
                        isPending = true;
                        break;
                    }
                }
            } else if (!type.isEmpty() && !taskId.isEmpty()) {
                isPending = true;
            }
            if (!isPending) return;

            if (triggerMs <= now) {
                LogWriter.log(TAG, "massSend recovery: executing expired task " + taskId);
                if (sH != null) {
                    sH.post(() -> executeMassSendFromPrefs());
                }
            } else {
                scheduleMassSend(triggerMs);
                LogWriter.log(TAG, "massSend recovery: re-scheduled task " + taskId);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "massSend recovery err: " + t.getMessage());
        }
    }

    public static void initOnAppStart(ClassLoader cl) {
        try {
            if (sCtx == null) {
                sCtx = com.leshao.v3.ContextManager.getAppContext();
            }
            if (sCL == null && cl != null) {
                sCL = cl;
            }
            ensureReceiverRegistered();
            recoverMassSendTask();
            hookP06Bypass(sCL);
            hookF9Debug();
            hookSendMsgMgrDebug();
            hookVideoSendDebug();
            LogWriter.log(TAG, "initOnAppStart OK v815 kl5.s5.Dj+thumb build=v430 2026-08-24");
        } catch (Throwable t) {
            LogWriter.log(TAG, "initOnAppStart err: " + t.getMessage());
        }
    }

    private static void hookSendMsgMgrDebug() {
        // Debug hook - disabled for 8.0.78 (kl5.s5 not found)
    }

    private static void hookVideoSendDebug() {
        // Debug hook - disabled for 8.0.78 (classes not found)
    }
    private static void hookF9Debug() {
        try {
            if (sCL == null) return;
            Class<?> f9 = XposedHelpers.findClass("com.tencent.mm.storage.f9", sCL);
            XposedBridge.hookAllMethods(f9, "Ra", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    StringBuilder sb = new StringBuilder("f9.Ra(");
                    for (int i = 0; i < p.args.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(p.args[i] != null ? p.args[i].toString() : "null");
                    }
                    sb.append(")");
                    XposedBridge.log("LeShaoV3: [DIAG] " + sb.toString());
                    LogWriter.log(TAG, "DIAG f9.Ra: " + p.args.length + " args");
                }
            });
            XposedBridge.hookAllMethods(f9, "I9", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    StringBuilder sb = new StringBuilder("f9.I9(");
                    for (int i = 0; i < p.args.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(p.args[i] != null ? p.args[i].toString() : "null");
                    }
                    sb.append(")");
                    XposedBridge.log("LeShaoV3: [DIAG] " + sb.toString());
                }
            });
            XposedBridge.log("LeShaoV3: [DIAG] f9 hooks installed OK");
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: [DIAG] f9 hook FAIL: " + t.getMessage());
        }
    }

    public static class MassSendReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            try {
                LogWriter.log(TAG, "massSend receiver triggered");
                executeMassSendFromPrefs();
            } catch (Throwable t) {
                LogWriter.log(TAG, "massSend receiver err: " + t.getMessage());
            }
        }
    }

    private static void executeMassSendFromPrefs() {
        if (sMassSendRunning) {
            LogWriter.log(TAG, "massSend from prefs: already running, skip");
            return;
        }
        sMassSendRunning = true;
        try {
            String type = WmPrefs.getStr("mass_send_type", "");
            if (type.isEmpty()) { sMassSendRunning = false; return; }
            String text = WmPrefs.getStr("mass_send_text", "");
            String targetJson = WmPrefs.getStr("mass_send_targets", "");
            String imgJson = WmPrefs.getStr("mass_send_images", "");
            String videoPath = WmPrefs.getStr("mass_send_video", "");
            String audioPath = WmPrefs.getStr("mass_send_audio", "");
            String taskId = WmPrefs.getStr("mass_send_task_id", "");
            boolean delay = WmPrefs.get("mass_send_delay", true);

            LogWriter.log(TAG, "massSend from prefs: type=" + type + " textLen=" + text.length()
                + " targets=" + targetJson + " videoPath=" + videoPath + " imgCount=" + imgJson);

            if (sCL == null) {
                LogWriter.log(TAG, "massSend from prefs: sCL is null, will retry on next window open");
                sMassSendRunning = false;
                return;
            }

            java.util.List<String> targets = new ArrayList<>();
            if (!targetJson.isEmpty()) {
                JSONArray arr = new JSONArray(targetJson);
                for (int i = 0; i < arr.length(); i++) targets.add(arr.getString(i));
            }

            java.util.List<String> imgList = new ArrayList<>();
            if (!imgJson.isEmpty()) {
                JSONArray arr = new JSONArray(imgJson);
                for (int i = 0; i < arr.length(); i++) imgList.add(arr.getString(i));
            }

            if (targets.isEmpty()) {
                LogWriter.log(TAG, "massSend from prefs: no targets, skip");
                WmPrefs.setStr("mass_send_type", "");
                WmPrefs.setStr("mass_send_trigger_ms", "");
                sMassSendRunning = false;
                return;
            }

            executeMassSendOnWorker(type, text, targets, imgList, videoPath, audioPath, taskId, delay);
        } catch (Throwable t) {
            LogWriter.log(TAG, "executeMassSendFromPrefs err: " + t.getMessage());
            sMassSendRunning = false;
        }
    }

    private static void executeMassSendOnWorker(String type, String text, java.util.List<String> targets,
                                                 java.util.List<String> imgList, String videoPath,
                                                 String audioPath, String taskId, boolean delay) {
        new Thread(() -> executeMassSend(type, text, targets, imgList, videoPath, audioPath, taskId, delay),
                "mass-send-worker").start();
    }

    // ==================== 发送执行 ====================

private static void executeMassSend(String type, String text, java.util.List<String> targets,
                                         java.util.List<String> imgList, String videoPath,
                                         String audioPath, String taskId, boolean delay) {
        try {
            updateRecordStatus("running");
            int success = 0, fail = 0;
            java.util.Random rand = new java.util.Random();

            for (String target : targets) {
                try {
                    if (!text.isEmpty()) {
                        WmReflect.sendTextMsg(sCL, text, target);
                    }

                    switch (type) {
                        case "image":
                            LogWriter.log(TAG, "massSend img: target=" + target + " listSize=" + imgList.size());
                            for (String imgPath : imgList) {
                                if (!imgPath.isEmpty()) sendImageToUser(target, imgPath);
                                else LogWriter.log(TAG, "massSend img empty path");
                            }
                            break;
                        case "video":
                            LogWriter.log(TAG, "massSend vid: target=" + target + " videoPath=" + videoPath);
                            if (videoPath != null && !videoPath.isEmpty())
                                sendVideoToUser(target, videoPath);
                            else
                                LogWriter.log(TAG, "massSend vid EMPTY path, skip");
                            for (String imgPath : imgList) {
                                if (!imgPath.isEmpty()) sendImageToUser(target, imgPath);
                            }
                        break;
                    case "image_text":
                        for (String imgPath : imgList) {
                            if (!imgPath.isEmpty()) sendImageToUser(target, imgPath);
                        }
                        break;
                    case "video_text":
                        if (videoPath != null && !videoPath.isEmpty())
                            sendVideoToUser(target, videoPath);
                        for (String imgPath : imgList) {
                            if (!imgPath.isEmpty()) sendImageToUser(target, imgPath);
                        }
                        break;
                    case "voice":
                        if (audioPath != null && !audioPath.isEmpty()) {
                            boolean voiceOk = sendAudioFile(target, audioPath);
                            LogWriter.log(TAG, "massSend voice: target=" + target + " ok=" + voiceOk + " audio=" + audioPath);
                            if (!voiceOk) throw new RuntimeException("voice send failed");
                        }
                        break;
                    default:
                        break;
                }
                success++;
            } catch (Throwable t) {
                fail++;
                LogWriter.log(TAG, "massSend fail: target=" + target + " type=" + type + " err=" + t.getMessage());
                saveMassSendFailRecord(target, type, t.getMessage());
            }

            if (delay && targets.size() > 1) {
                Thread.sleep(1000 + rand.nextInt(9000));
            }
        }

        LogWriter.log(TAG, "massSend done: success=" + success + " fail=" + fail);
        updateRecordStatus("completed", success, fail);

        WmPrefs.setStr("mass_send_type", "");
        WmPrefs.setStr("mass_send_text", "");
        WmPrefs.setStr("mass_send_targets", "");
        WmPrefs.setStr("mass_send_images", "");
        WmPrefs.setStr("mass_send_video", "");
        WmPrefs.setStr("mass_send_audio", "");
        WmPrefs.setStr("mass_send_task_id", "");
        WmPrefs.setStr("mass_send_trigger_ms", "");
    } catch (Throwable t) {
        LogWriter.log(TAG, "massSend err: " + t.getMessage());
    } finally {
        sMassSendRunning = false;
    }
    }

    private static void updateRecordStatus(String status) {
        updateRecordStatus(status, 0, 0);
    }

    private static void updateRecordStatus(String status, int success, int fail) {
        try {
            JSONArray records;
            String existing = WmPrefs.getStr("mass_send_records", "");
            if (existing.isEmpty()) return;
            records = new JSONArray(existing);
            for (int i = records.length() - 1; i >= 0; i--) {
                JSONObject obj = records.getJSONObject(i);
                if ("pending".equals(obj.optString("status", ""))
                        || "running".equals(obj.optString("status", ""))) {
                    obj.put("status", status);
                    if (success > 0 || fail > 0) {
                        obj.put("success", success);
                        obj.put("fail", fail);
                    }
                    WmPrefs.setStr("mass_send_records", records.toString());
                    break;
                }
            }
         } catch (Exception e) { LogWriter.log(TAG, "WmChatHook error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
    }
    private static long getNextMsgId() {
        android.database.Cursor c = null;
        try {
            c = rawQueryMsg("SELECT MAX(msgId) FROM message", null);
            if (c == null) {
                XposedBridge.log("LeShaoV3: getNextMsgId CURSOR NULL");
                LogWriter.log(TAG, "getNextMsgId: cursor null");
                return System.currentTimeMillis();
            }
            if (c.moveToFirst()) {
                long max = c.getLong(0);
                long next = max + 1;
                XposedBridge.log("LeShaoV3: getNextMsgId max=" + max + " next=" + next);
                LogWriter.log(TAG, "getNextMsgId: max=" + max + " next=" + next);
                return next;
            }
            XposedBridge.log("LeShaoV3: getNextMsgId moveToFirst=false count=" + c.getCount());
            LogWriter.log(TAG, "getNextMsgId: moveToFirst=false count=" + c.getCount());
        } catch (Throwable t) {
            XposedBridge.log("LeShaoV3: getNextMsgId err: " + t.getMessage());
            LogWriter.log(TAG, "getNextMsgId err: " + t.getMessage());
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignored) {} }
        }
        return System.currentTimeMillis();
    }

    public static boolean hookP06BypassEarly(ClassLoader cl) {
        // Try immediate bypass (before DexKit scan)
        boolean immediate = hookP06Bypass(cl);
        if (immediate) return true;

        // Register post-scan callback: retry after DexKit scan completes
        DexKitHelper.addPostScanCallback(() -> {
            LogWriter.log(TAG, "hookP06Bypass: retrying after DexKit scan");
            hookP06Bypass(cl);
        });
        return false;
    }

    static boolean hookP06Bypass(ClassLoader cl) {
        // Priority 1: use DexKit scan result
        if (DexKitHelper.isScanComplete()) {
            String p06Name = DexKitHelper.getP06ClassName();
            if (p06Name != null) {
                try {
                    Class<?> p06 = XposedHelpers.findClass(p06Name, cl);
                    XposedBridge.hookAllMethods(p06, "b", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getThrowable() != null) {
                                param.setThrowable(null);
                                param.setResult(null);
                            }
                        }
                    });
                    LogWriter.log(TAG, "hookP06Bypass OK (DexKit): " + p06Name + ".b hooked");
                    return true;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "hookP06Bypass DexKit class failed: " + e.getMessage());
                }
            }
        }

        // Priority 2: try known packages
        String[] pkgs = {
            "com.tencent.mm", "com.tencent.mm.model", "com.tencent.mm.storage",
            "com.tencent.mm.modelmulti", "com.tencent.mm.sdk", "com.tencent.mm.kernel",
            "com.tencent.mm.plugin.messenger", "com.tencent.mm.plugin.messenger.foundation",
            "com.tencent.mm.cb", "com.tencent.mm.bootstrap",
            "com.tencent.mm.app", "com.tencent.mm.ui",
            "com.tencent.mm.modelstat", "com.tencent.mm.modelsns",
            "com.tencent.mm.platformtools", "com.tencent.mm.protocal",
            "com.tencent.mm.network", "com.tencent.mm.algorithm",
            "com.tencent.mm.compatible"
        };
        for (String pkg : pkgs) {
            try {
                Class<?> p06 = XposedHelpers.findClass(pkg + ".p06", cl);
                XposedBridge.hookAllMethods(p06, "b", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getThrowable() != null) {
                            param.setThrowable(null);
                            param.setResult(null);
                        }
                    }
                });
                LogWriter.log(TAG, "hookP06Bypass OK: " + pkg + ".p06.b hooked");
                return true;
            } catch (Throwable ignored) {}
        }
        try {
            Class<?> p06 = XposedHelpers.findClass("p06", cl);
            XposedBridge.hookAllMethods(p06, "b", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null) {
                        param.setThrowable(null);
                        param.setResult(null);
                    }
                }
            });
            LogWriter.log(TAG, "hookP06Bypass OK: p06.b (default pkg) hooked");
            return true;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field f = ClassLoader.class.getDeclaredField("classes");
            f.setAccessible(true);
            java.util.Vector<Class<?>> classes = (java.util.Vector<Class<?>>) f.get(cl);
            for (Class<?> c : classes) {
                if (c.getName().endsWith(".p06") || c.getSimpleName().equals("p06")) {
                    LogWriter.log(TAG, "hookP06Bypass OK via brute: " + c.getName());
                    XposedBridge.hookAllMethods(c, "b", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getThrowable() != null) {
                                param.setThrowable(null);
                                param.setResult(null);
                            }
                        }
                    });
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        if (!DexKitHelper.isScanComplete()) {
            return false;
        }
        LogWriter.log(TAG, "hookP06Bypass FAILED: p06 class NOT found in any package");
        return false;
    }

private static boolean sendImageToUser(String toUser, String imgPath) {
        LogWriter.log(TAG, "v866 sendImage ENTER: to=" + toUser + " path=" + imgPath);
        if (sCL == null) throw new RuntimeException("sCL null");
        java.io.File f = new java.io.File(imgPath);
        if (!f.exists()) throw new RuntimeException("file not found: " + imgPath);
        try {
            Object sendMgr = WmReflect.getSendMsgMgr(sCL);
            if (sendMgr == null) throw new RuntimeException("SendMsgMgr null");
            boolean ok = false;
            // 8.0.78(3180): 图片走 qs5.v5.b(Context,toUser,fileName,i,...,k7,d) sendImg (旧 wj 已失效)
            ok |= invokeSendImgViaB(sendMgr, toUser, imgPath);
            if (!ok) throw new RuntimeException("sendImg(b) failed");
            LogWriter.log(TAG, "v866 sendImage b ok to=" + toUser);
        } catch (Throwable t) {
            LogWriter.log(TAG, "v866 sendImage fail: " + t.getClass().getName() + ": " + t.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            LogWriter.log(TAG, "v866 sendImage stack: " + sw.toString());
            throw new RuntimeException(t);
        }
        return true;
    }

    /**
     * 8.0.78(3180): qs5.v5.b = sendImg(CDN)。多版本签名逐一尝试。
     * 常见签名: b(Context,toUser,fileName,int,mode,...,k7,d)
     */
    private static boolean invokeSendImgViaB(Object sendMgr, String toUser, String imgPath) {
        java.lang.reflect.Method[] ms = sendMgr.getClass().getDeclaredMethods();
        // 穷举参数个数 7..16, 逐个尝试
        for (java.lang.reflect.Method m : ms) {
            if (!m.getName().equals("b")) continue;
            try {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 4 || pts.length > 18) continue;
                Object[] args = new Object[pts.length];
                for (int i = 0; i < pts.length; i++) {
                    Class<?> p = pts[i];
                    if (i == 0 && (p == android.content.Context.class || p.getName().endsWith("Context"))) {
                        args[i] = sCtx;
                    } else if (i == 1 && p == String.class) {
                        args[i] = toUser;
                    } else if (i == 2 && p == String.class) {
                        args[i] = imgPath;
                    } else if (i == 3 && p == int.class) {
                        args[i] = 4; // 原图
                    } else if (p == int.class) {
                        args[i] = 0;
                    } else if (p == long.class) {
                        args[i] = 0L;
                    } else if (p == boolean.class) {
                        args[i] = false;
                    } else if (p == String.class) {
                        args[i] = "";
                    } else {
                        args[i] = null;
                    }
                }
                m.setAccessible(true);
                m.invoke(sendMgr, args);
                LogWriter.log(TAG, "sendImage via b: params=" + pts.length);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

private static boolean sendVideoToUser(String toUser, String videoPath) {
        LogWriter.log(TAG, "v866 sendVideo ENTER: to=" + toUser + " path=" + videoPath);
        if (sCL == null) throw new RuntimeException("sCL null");
        java.io.File f = new java.io.File(videoPath);
        if (!f.exists()) throw new RuntimeException("file not found: " + videoPath);
        int duration = getVideoDuration(videoPath);
        LogWriter.log(TAG, "v866 sendVideo duration=" + duration + "s size=" + f.length());

        Object sendMgr = WmReflect.getSendMsgMgr(sCL);
        if (sendMgr == null) throw new RuntimeException("SendMsgMgr null");
        final Object mgr = sendMgr;
        final String finalToUser = toUser;
        final int finalDuration = duration;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] sentResult = {false};
        final Throwable[] sentError = {null};
        sH.post(() -> {
            try {
                java.io.File tempThumb = new java.io.File(sCtx.getCacheDir(), "thumb_" + System.currentTimeMillis() + ".jpg");
                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                try {
                    retriever.setDataSource(videoPath);
                    android.graphics.Bitmap thumb = retriever.getFrameAtTime(1000000);
                    java.io.FileOutputStream thumbFos = new java.io.FileOutputStream(tempThumb);
                    thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, thumbFos);
                    thumbFos.close();
                    thumb.recycle();
                } finally {
                    retriever.release();
                }
                LogWriter.log(TAG, "v866 thumb generated: " + tempThumb.getAbsolutePath());

                boolean ok = invokeSendVideoViaSjTj(mgr, finalToUser, videoPath, tempThumb.getAbsolutePath(), finalDuration);
                LogWriter.log(TAG, "v866 sendVideo invoked=" + ok);
                if (!ok) throw new RuntimeException("sendVideo(sj/tj) failed");
                sentResult[0] = true;
            } catch (Throwable t) {
                LogWriter.log(TAG, "v866 sendVideo fail: " + t.getClass().getName() + ": " + t.getMessage());
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                LogWriter.log(TAG, "v866 sendVideo stack: " + sw.toString());
                sentError[0] = t;
            } finally {
                latch.countDown();
            }
        });
        try {
            boolean finished = latch.await(60, TimeUnit.SECONDS);
            if (!finished) {
                LogWriter.log(TAG, "v866 sendVideo timeout for " + finalToUser);
                throw new RuntimeException("video send timeout for " + finalToUser);
            }
            if (sentError[0] != null) {
                throw new RuntimeException("video send fail: " + sentError[0].getMessage());
            }
            LogWriter.log(TAG, "v866 sendVideo done: to=" + finalToUser + " sent=" + sentResult[0]);
            return sentResult[0];
        } catch (InterruptedException e) {
            LogWriter.log(TAG, "v866 sendVideo interrupted for " + finalToUser);
            throw new RuntimeException("video send interrupted for " + finalToUser, e);
        }
    }

    /** 8.0.78(3180): qs5.v5.sj/tj = sendVedio(CDN)。穷举方法签名带 Context 的那组。 */
    private static boolean invokeSendVideoViaSjTj(Object sendMgr, String toUser, String videoPath, String thumbPath, int durationSec) {
        for (java.lang.reflect.Method m : sendMgr.getClass().getDeclaredMethods()) {
            if (!m.getName().equals("sj") && !m.getName().equals("tj")) continue;
            try {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 4 || pts.length > 20) continue;
                boolean hasCtx = false;
                for (Class<?> p : pts) {
                    if (p == android.content.Context.class || p.getName().endsWith("Context")) {
                        hasCtx = true;
                        break;
                    }
                }
                if (!hasCtx) continue;
                Object[] args = new Object[pts.length];
                int strIdx = 0;
                int intIdx = 0;
                for (int i = 0; i < pts.length; i++) {
                    Class<?> p = pts[i];
                    if (p == android.content.Context.class || p.getName().endsWith("Context")) {
                        args[i] = sCtx;
                    } else if (p == int.class) {
                        if (intIdx == 0) { args[i] = 62; }
                        else if (intIdx == 1) { args[i] = durationSec; }
                        else { args[i] = 0; }
                        intIdx++;
                    } else if (p == long.class) {
                        args[i] = 0L;
                    } else if (p == boolean.class) {
                        args[i] = false;
                    } else if (p == String.class) {
                        if (strIdx == 0) { args[i] = toUser; }
                        else if (strIdx == 1) { args[i] = videoPath; }
                        else if (strIdx == 2) { args[i] = thumbPath; }
                        else { args[i] = ""; }
                        strIdx++;
                    } else {
                        args[i] = null;
                    }
                }
                m.setAccessible(true);
                m.invoke(sendMgr, args);
                LogWriter.log(TAG, "sendVideo via " + m.getName() + ": params=" + pts.length);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static int getVideoDuration(String path) {
        try {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(path);
            String dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            retriever.release();
            if (dur != null) return Integer.parseInt(dur) / 1000;
        } catch (Throwable t) {
            LogWriter.log(TAG, "getVideoDuration err: " + t.getMessage());
        }
        return 10;
    }

    private static void copyMediaToWxDir(String srcPath, Object msg) {
        try {
            if (srcPath == null || !new java.io.File(srcPath).exists()) return;
            Object u0Service = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("pa5.n0", sCL), "c",
                XposedHelpers.findClass("qh3.u0", sCL));
            Object y_j = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("lin5.y", sCL), "j");
            String ext = srcPath.substring(srcPath.lastIndexOf('.'));
            String dstPath = (String) XposedHelpers.callMethod(
                u0Service, "Nj", y_j, System.currentTimeMillis() + ext, false, true);
            if (dstPath == null) return;
            new java.io.File(dstPath).getParentFile().mkdirs();
            java.io.FileInputStream fis = null;
            java.io.FileOutputStream fos = null;
            try {
                fis = new java.io.FileInputStream(new java.io.File(srcPath));
                fos = new java.io.FileOutputStream(new java.io.File(dstPath));
                byte[] buf = new byte[16384];
                int n;
                while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                if (fis != null) { try { fis.close(); } catch (Throwable t) { LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + t.getMessage()); } }
                if (fos != null) { try { fos.close(); } catch (Throwable t) { LogWriter.log(TAG, "WmChatHook error: " + t.getClass().getSimpleName() + " " + t.getMessage()); } }
            }
            XposedHelpers.callMethod(msg, "j1", dstPath);
        } catch (Throwable t) {
            LogWriter.log(TAG, "copyMediaToWxDir error: " + t.getMessage());
        }
    }

    private static Object getMsgInfoStorage() {
        try {
            if (sCL == null) { LogWriter.log(TAG, "getMsgInfoStorage: sCL null"); return null; }
            Class<?> shortCls = com.leshao.v3.hook.VersionCompat.findMsgStorageShortClass(sCL);
            if (shortCls == null) { LogWriter.log(TAG, "getMsgInfoStorage: shortCls null"); return null; }
            Object service = XposedHelpers.callStaticMethod(shortCls, "b");
            if (service == null) { LogWriter.log(TAG, "getMsgInfoStorage: service null"); return null; }
            Object result = XposedHelpers.callMethod(service, "u");
            XposedBridge.log("LeShaoV3: getMsgInfoStorage result=" + (result != null ? result.getClass().getName() : "null"));
            return result;
        } catch (Throwable t) {
            LogWriter.log(TAG, "getMsgInfoStorage err: " + t.getMessage());
            XposedBridge.log("LeShaoV3: getMsgInfoStorage err: " + t.getMessage());
            return null;
        }
    }

    private static boolean sendAudioFile(String talker, String filePath) {
        try {
            if (sCL == null) return false;

            String lower = filePath.toLowerCase();
            if (lower.endsWith(".mp3")) {
                LogWriter.log(TAG, "sendAudioFile: mp3 -> sendMp3Voice");
                return TtsVoiceSender.sendMp3Voice(talker, filePath);
            }

            // AMR/SILK 文件直接走 SceneVoice 发送
            if (lower.endsWith(".amr") || lower.endsWith(".silk")) {
                int durationMs = estimateAmrDuration(filePath);
                LogWriter.log(TAG, "sendAudioFile: direct amr/silk duration=" + durationMs);
                return TtsVoiceSender.sendViaSceneVoice(talker, filePath, durationMs);
            }

            // 兜底：尝试 MP3 转码
            LogWriter.log(TAG, "sendAudioFile: unknown ext, try mp3 fallback");
            return TtsVoiceSender.sendMp3Voice(talker, filePath);
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendAudioFile err: " + t.getMessage());
            return false;
        }
    }

    private static int estimateAmrDuration(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return 0;
            // AMR-NB 帧大小约 32 字节/20ms；SILK 按 AMR 估算
            return (int) (f.length() / 32.0 * 20.0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String findFile(java.io.File dir, String name) {
        try {
            if (!dir.exists() || !dir.isDirectory()) return null;
            java.io.File[] files = dir.listFiles();
            if (files == null) return null;
            for (java.io.File f : files) {
                if (f.isDirectory()) {
                    String r = findFile(f, name);
                    if (r != null) return r;
                } else if (f.getName().contains(name)) {
                    return f.getAbsolutePath();
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "findFile error: " + t.getMessage());
        }
        return null;
    }
}
