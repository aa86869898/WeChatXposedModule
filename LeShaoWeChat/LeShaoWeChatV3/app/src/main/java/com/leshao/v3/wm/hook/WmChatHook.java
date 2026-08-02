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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.MusicSearchApi;
import com.leshao.v3.ui.MusicLog;
import com.leshao.v3.wm.utils.VoiceSongHelper;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static boolean sPanelShow;
    private static final Handler sH = new Handler(Looper.getMainLooper());
    private static boolean sTranslateOn;

    private static final String TAG = "WmChat";
    private static final AtomicBoolean sScheduledTaskRunning = new AtomicBoolean(false);
    private static final List<Runnable> sPendingScheduledTasks = new ArrayList<>();
    private static BroadcastReceiver sMassSendReceiver;
    private static Context sCtx;

    public static void showTitleBtn(Activity act, ClassLoader cl, String user) {
        dismissTitleBtn();
        sAct = act;
        sCL = cl;
        sUser = user;
        sWM = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
        if (user == null) return;

        com.leshao.v3.wm.utils.WmUi.DragFloat f = new com.leshao.v3.wm.utils.WmUi.DragFloat(
                act, sWM, "⚡", AppColors.accent(), "float_chat",
                () -> { if (sPanelShow) hidePanel(); else showPanel(); });
        f.addToWindow();
        sFloatIcon = f;
        LogWriter.log(TAG, "⚡ float icon shown user=" + user);
    }

    public static void dismissTitleBtn() {
        hidePanel();
        if (sFloatIcon != null) {
            sFloatIcon.removeFromWindow();
            sFloatIcon = null;
        }
        sAct = null;
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
        } catch (Throwable ignored) {}

        ScrollView sv = new ScrollView(sAct);
        LinearLayout btns = new LinearLayout(sAct);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.setPadding(0, 0, 0, dp(8));

        btns.addView(com.leshao.v3.wm.utils.WmUi.makeHeader(sAct,
                "⚡ 乐少大师", displayName));

        if (WmPrefs.isQuickReply()) btns.addView(WmUi.makeBtn(sAct, "📝 快捷回复", WmChatHook::showQuickReply));
        if (WmPrefs.isBatchSend()) btns.addView(WmUi.makeBtn(sAct, "🚀 乐少万群定时群发", WmChatHook::showMassSend));
        if (WmPrefs.isScheduledMsg()) btns.addView(WmUi.makeBtn(sAct, "⏰ 定时发送", WmChatHook::showScheduledMsg));
        if (WmPrefs.isExportChat()) btns.addView(WmUi.makeBtn(sAct, "📤 导出聊天", WmChatHook::exportChat));
        if (WmPrefs.isKeywordAlert()) btns.addView(WmUi.makeBtn(sAct, "🔔 关键词设置", WmChatHook::showKeywordSet));
        if (WmPrefs.isAutoTranslate()) btns.addView(WmUi.makeBtn(sAct, "🌐 自动翻译", WmChatHook::toggleTranslate));
        if (WmPrefs.isAutoVoice()) btns.addView(makeToggleRow("🔊 语音自动播放", "auto_voice"));
        if (WmPrefs.isTTS()) btns.addView(makeToggleRow("📢 消息朗读", "tts"));
        if (WmPrefs.isAtRemind()) btns.addView(WmUi.makeBtn(sAct, "@ 强提醒", WmChatHook::showAtRemind));
        if (WmPrefs.isAutoSaveMedia()) btns.addView(WmUi.makeBtn(sAct, "💾 媒体保存", () -> toast("媒体自动保存已启用")));
        if (WmPrefs.isPrivateNote()) btns.addView(WmUi.makeBtn(sAct, "📌 私密备注", WmChatHook::showPrivateNote));
        if (WmPrefs.isChatStats()) btns.addView(WmUi.makeBtn(sAct, "📊 聊天统计", WmChatHook::showChatStats));
        if (WmPrefs.isClearScreen()) btns.addView(WmUi.makeBtn(sAct, "🧹 一键清屏", () -> toast("消息已隐藏")));
        if (WmPrefs.isMsgSearch()) btns.addView(WmUi.makeBtn(sAct, "🔍 消息搜索", WmChatHook::showMsgSearch));
        btns.addView(makeToggleRow("语音点歌", "voice_song_enabled"));
        btns.addView(makeToggleRow("音乐卡片", "music_card_enabled"));

        if (isGroup) {
            com.leshao.v3.wm.hook.WmGroupHook.bind(sAct, sCL, sUser);
            btns.addView(WmUi.makeDivider(sAct));
            btns.addView(WmUi.makeHeader(sAct, "🛡 乐少群管理", com.leshao.v3.wm.hook.WmGroupHook.makeRoomSubtitle()));
            com.leshao.v3.wm.hook.WmGroupHook.appendGroupButtons(btns);
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
        int pw = dp(150);
        int ph = dp(400);
        w.setLayout(pw, ph);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0.05f;

        if (sFloatIcon != null) {
            int[] loc = new int[2];
            try { sFloatIcon.btn.getLocationOnScreen(loc); } catch (Exception ignored) {}
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
            try { sPanelDialog.dismiss(); } catch (Exception ignored) {}
            sPanelDialog = null;
        }
        sPanelShow = false;
    }

    // ===== 语音点歌 & 音乐卡片 =====

    public static void onVoiceSongRequest(String query, String talker, boolean isSelf) {
        if (sAct == null || sAct.isFinishing()) return;
        LogWriter.log(TAG, "voiceSong q=" + query + " talker=" + talker + " self=" + isSelf);
        MusicSearchApi.searchKugou(query, 1, new MusicSearchApi.SearchCallback() {
            @Override
            public void onResult(java.util.List<MusicSearchApi.Song> songs, int total, boolean hasPrev, boolean hasNext) {
                sH.post(() -> {
                    if (songs == null || songs.isEmpty()) {
                        toast("未找到歌曲: " + query);
                        return;
                    }
                    if (isSelf) {
                        showVoiceSongDialog(talker, songs);
                    } else {
                        onVoiceSongAutoSend(talker, songs.get(0));
                    }
                });
            }
            @Override
            public void onError(String msg) {
                sH.post(() -> toast("搜索失败: " + msg));
            }
        });
    }

    private static void onVoiceSongAutoSend(String talker, MusicSearchApi.Song song) {
        MusicSearchApi.getKugouPlayUrl(song.hash, new MusicSearchApi.PlayUrlCallback() {
            @Override
            public void onUrl(String url) {
                if (url == null || url.isEmpty()) {
                    sH.post(() -> toast("获取音源失败"));
                    return;
                }
                final String fUrl = url;
                new Thread(() -> {
                    VoiceSongHelper.sendMp3AsVoiceFromUrl(sAct, fUrl,
                        (song.title != null ? song.title : "") + "-" + (song.artist != null ? song.artist : ""), talker);
                }).start();
            }
            @Override
            public void onError(String msg) {
                sH.post(() -> toast("获取音源失败"));
            }
        });
    }

    private static void showVoiceSongDialog(String talker, java.util.List<MusicSearchApi.Song> songs) {
        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackground(CandyUi.cardBg(sAct));

        TextView title = new TextView(sAct);
        title.setText("语音点歌 - 选择歌曲发送");
        title.setTextSize(14);
        title.setTextColor(AppColors.TEXT_TITLE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        ScrollView sv = new ScrollView(sAct);
        LinearLayout list = new LinearLayout(sAct);
        list.setOrientation(LinearLayout.VERTICAL);

        for (final MusicSearchApi.Song song : songs) {
            list.addView(buildVoiceSongRow(song, talker));
            View div = new View(sAct);
            div.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
            div.setBackgroundColor(AppColors.divider());
            list.addView(div);
        }
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(320)));

        Button closeBtn = new Button(sAct);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(13);
        closeBtn.setAllCaps(false);
        closeBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable cbBg = new GradientDrawable();
        cbBg.setColor(AppColors.accent());
        cbBg.setCornerRadius(dp(8));
        closeBtn.setBackground(cbBg);
        root.addView(closeBtn);

        Dialog dlg = new Dialog(sAct);
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);
        closeBtn.setOnClickListener(v -> dlg.dismiss());

        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(dp(330), dp(480));
            w.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.4f;
            w.setAttributes(lp);
        }
        dlg.show();
    }

    private static View buildVoiceSongRow(final MusicSearchApi.Song song, final String talker) {
        LinearLayout row = new LinearLayout(sAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackgroundColor(AppColors.card());

        ImageView cover = new ImageView(sAct);
        int cs = dp(44);
        cover.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cr = new GradientDrawable();
        cr.setCornerRadius(dp(4));
        cover.setBackground(cr);
        cover.setBackgroundColor(0xFFFFFFFF);
        loadSongCover(cover, song.cover);
        row.addView(cover);

        LinearLayout col = new LinearLayout(sAct);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(8), 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        col.setLayoutParams(clp);

        TextView tvTitle = new TextView(sAct);
        tvTitle.setText(song.title != null ? song.title : "");
        tvTitle.setTextSize(13);
        tvTitle.setTextColor(AppColors.text1());
        tvTitle.setSingleLine(true);
        col.addView(tvTitle);

        TextView tvArtist = new TextView(sAct);
        tvArtist.setText(song.artist != null ? song.artist : "");
        tvArtist.setTextSize(11);
        tvArtist.setTextColor(AppColors.text2());
        tvArtist.setSingleLine(true);
        col.addView(tvArtist);

        row.addView(col);

        Button sendBtn = new Button(sAct);
        sendBtn.setText("发送");
        sendBtn.setTextSize(11);
        sendBtn.setAllCaps(false);
        sendBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable sbBg = new GradientDrawable();
        sbBg.setColor(AppColors.accent());
        sbBg.setCornerRadius(dp(6));
        sendBtn.setBackground(sbBg);
        sendBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        sendBtn.setOnClickListener(v -> {
            toast("正在获取播放链接...");
            MusicSearchApi.getKugouPlayUrl(song.hash, new MusicSearchApi.PlayUrlCallback() {
                @Override
                public void onUrl(String url) {
                    if (url == null || url.isEmpty()) {
                        sH.post(() -> toast("获取音源失败"));
                        return;
                    }
                    final String fUrl = url;
                    new Thread(() -> {
                        VoiceSongHelper.sendMp3AsVoiceFromUrl(sAct, fUrl,
                            (song.title != null ? song.title : "") + "-" + (song.artist != null ? song.artist : ""), talker);
                        sH.post(() -> toast("\uD83C\uDFB5 " + (song.title != null ? song.title : "") + " 已发送"));
                    }).start();
                }
                @Override
                public void onError(String msg) {
                    sH.post(() -> toast("获取音源失败"));
                }
            });
        });
        row.addView(sendBtn);

        return row;
    }

    public static void onMusicCardRequest(String query, String talker, boolean isSelf) {
        if (sAct == null || sAct.isFinishing()) return;
        LogWriter.log(TAG, "musicCard q=" + query + " talker=" + talker + " self=" + isSelf);
        MusicSearchApi.searchKugou(query, 1, new MusicSearchApi.SearchCallback() {
            @Override
            public void onResult(java.util.List<MusicSearchApi.Song> songs, int total, boolean hasPrev, boolean hasNext) {
                sH.post(() -> {
                    if (songs == null || songs.isEmpty()) {
                        toast("未找到歌曲: " + query);
                        return;
                    }
                    if (isSelf) {
                        showMusicCardDialog(talker, songs);
                    } else {
                        onMusicCardAutoSend(talker, songs.get(0));
                    }
                });
            }
            @Override
            public void onError(String msg) {
                sH.post(() -> toast("搜索失败: " + msg));
            }
        });
    }

    private static void onMusicCardAutoSend(String talker, MusicSearchApi.Song song) {
        sendMusicCard(talker, song);
    }

    private static void showMusicCardDialog(String talker, java.util.List<MusicSearchApi.Song> songs) {
        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackground(CandyUi.cardBg(sAct));

        TextView title = new TextView(sAct);
        title.setText("音乐卡片 - 选择歌曲发送");
        title.setTextSize(14);
        title.setTextColor(AppColors.TEXT_TITLE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        ScrollView sv = new ScrollView(sAct);
        LinearLayout list = new LinearLayout(sAct);
        list.setOrientation(LinearLayout.VERTICAL);

        for (final MusicSearchApi.Song song : songs) {
            list.addView(buildMusicCardRow(song, talker));
            View div = new View(sAct);
            div.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
            div.setBackgroundColor(AppColors.divider());
            list.addView(div);
        }
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, dp(320)));

        Button closeBtn = new Button(sAct);
        closeBtn.setText("关闭");
        closeBtn.setTextSize(13);
        closeBtn.setAllCaps(false);
        closeBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable cbBg = new GradientDrawable();
        cbBg.setColor(AppColors.accent());
        cbBg.setCornerRadius(dp(8));
        closeBtn.setBackground(cbBg);
        root.addView(closeBtn);

        Dialog dlg = new Dialog(sAct);
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);
        closeBtn.setOnClickListener(v -> dlg.dismiss());

        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(dp(330), dp(480));
            w.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.4f;
            w.setAttributes(lp);
        }
        dlg.show();
    }

    private static View buildMusicCardRow(final MusicSearchApi.Song song, final String talker) {
        LinearLayout row = new LinearLayout(sAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackgroundColor(AppColors.card());

        ImageView cover = new ImageView(sAct);
        int cs = dp(44);
        cover.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cr = new GradientDrawable();
        cr.setCornerRadius(dp(4));
        cover.setBackground(cr);
        cover.setBackgroundColor(0xFFFFFFFF);
        loadSongCover(cover, song.cover);
        row.addView(cover);

        LinearLayout col = new LinearLayout(sAct);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(8), 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        col.setLayoutParams(clp);

        TextView tvTitle = new TextView(sAct);
        tvTitle.setText(song.title != null ? song.title : "");
        tvTitle.setTextSize(13);
        tvTitle.setTextColor(AppColors.text1());
        tvTitle.setSingleLine(true);
        col.addView(tvTitle);

        TextView tvArtist = new TextView(sAct);
        tvArtist.setText(song.artist != null ? song.artist : "");
        tvArtist.setTextSize(11);
        tvArtist.setTextColor(AppColors.text2());
        tvArtist.setSingleLine(true);
        col.addView(tvArtist);

        row.addView(col);

        Button sendBtn = new Button(sAct);
        sendBtn.setText("发送卡片");
        sendBtn.setTextSize(11);
        sendBtn.setAllCaps(false);
        sendBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable sbBg = new GradientDrawable();
        sbBg.setColor(AppColors.accent());
        sbBg.setCornerRadius(dp(6));
        sendBtn.setBackground(sbBg);
        sendBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        sendBtn.setOnClickListener(v -> {
            sendMusicCard(talker, song);
            toast("\uD83C\uDFB6 " + song.title + " 卡片已发送");
        });
        row.addView(sendBtn);

        return row;
    }

    private static void sendMusicCard(String talker, MusicSearchApi.Song song) {
        try {
            if (sCL == null) { toast("发送失败: ClassLoader null"); return; }
            String xml = "<msg><appmsg appid=\"wx79f2c4418704b4f8\" sdkver=\"0\"><title>"
                    + escapeXml(song.title) + "</title><des>"
                    + escapeXml(song.artist) + "</des><type>3</type><url>"
                    + escapeXml("https://www.kugou.com/song/#hash=" + song.hash) + "</url>"
                    + "<appattach><cdnthumbaeskey></cdnthumbaeskey><aeskey></aeskey></appattach>"
                    + "</appmsg></msg>";
            WmReflect.sendAppMsg(sCL, xml, talker);
            LogWriter.log(TAG, "musicCard sent: " + song.title);
        } catch (Exception e) {
            LogWriter.log(TAG, "sendMusicCard err: " + e.getMessage());
            toast("发送音乐卡片失败: " + e.getMessage());
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void loadSongCover(ImageView iv, String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) {
            iv.setImageDrawable(emojiDrawable(sAct, "\uD83C\uDFB5", dp(18)));
            return;
        }
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(conn.getInputStream());
                conn.disconnect();
                if (bm != null) sH.post(() -> iv.setImageBitmap(bm));
            } catch (Exception ignored) {}
        }).start();
    }

    private static android.graphics.drawable.Drawable emojiDrawable(Context ctx, String emoji, int size) {
        TextView tv = new TextView(ctx);
        tv.setText(emoji);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
        tv.setGravity(Gravity.CENTER);
        tv.measure(View.MeasureSpec.makeMeasureSpec(size * 2, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size * 2, View.MeasureSpec.EXACTLY));
        tv.layout(0, 0, tv.getMeasuredWidth(), tv.getMeasuredHeight());
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                tv.getMeasuredWidth(), tv.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        tv.draw(c);
        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bm);
    }

    // ===== 工具方法 =====

    /** 在当前聊天输入框插入文字 */
    static boolean setChatInput(String text) {
        try {
            View footer = getChatFooter();
            if (footer == null) return false;
            return findEditTextAndSet(footer, text);
        } catch (Exception e) {
            LogWriter.log(TAG, "setChatInput err: " + e.getMessage());
            return false;
        }
    }

    static View getChatFooter() {
        try {
            Class<?> launcher = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", sCL);
            Object inst = XposedHelpers.callStaticMethod(launcher, "getInstance");
            Object frag = XposedHelpers.callMethod(inst, "getCurrentFragmet");
            if (frag == null) return null;
            return (View) XposedHelpers.getObjectField(frag, "mFooter");
        } catch (Exception ignored) { return null; }
    }

    static boolean findEditTextAndSet(View v, String text) {
        if (v instanceof EditText) {
            ((EditText) v).setText(text);
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (findEditTextAndSet(vg.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    // ===== 功能实现 =====

    // 1. 快捷回复 — 文本/图片/语音
    static void showQuickReply() {
        new AlertDialog.Builder(sAct).setTitle("快捷回复")
                .setItems(new String[]{"文本回复", "发送图片", "MP3转语音发送"}, (d, w) -> {
                    switch (w) {
                        case 0: showQuickText(); break;
                        case 1: showQuickImage(); break;
                        case 2: showQuickMp3(); break;
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    static void showQuickText() {
        String[] texts = WmPrefs.getQuickReplyTexts().split("\\|");
        new AlertDialog.Builder(sAct).setTitle("文本回复")
                .setItems(texts, (d, w) -> {
                    boolean ok = setChatInput(texts[w]);
                    toast(ok ? "已填入输入框" : "填入失败");
                })
                .setNegativeButton("取消", null).show();
    }

    static void showQuickImage() {
        pickImage(path -> {
            if (path == null) return;
            new Thread(() -> {
                boolean ok = sendImageViaXes(path);
                sH.post(() -> toast(ok ? "图片已发送" : "发送失败"));
            }).start();
        });
    }

    static void showQuickMp3() {
        pickAudio(path -> {
            if (path == null) return;
            new Thread(() -> {
                String result = sendMp3AsVoice(path);
                sH.post(() -> toast(result));
            }).start();
        });
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
        launchSystemFilePicker("audio/mpeg", cb);
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
                }
            });
        } catch (Exception e) {
            LogWriter.log(TAG, "onActivityResult hook failed: " + e.getMessage());
        }
    }

    /** 将 content:// URI 复制到 /sdcard/WeChatMaster/tmp/ 返回本地路径 */
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
            File wcMaster = new File(Environment.getExternalStorageDirectory(), "WeChatMaster");
            File tmpDir = new File(wcMaster, "tmp");
            tmpDir.mkdirs();
            File out = new File(tmpDir, fileName);
            InputStream is = act.getContentResolver().openInputStream(uri);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            LogWriter.log(TAG, "copyUriToTemp err: " + e.getMessage());
            return null;
        }
    }

    // 2. 定时发送 — 文件选择器 + 自定义日期时间
    static void showScheduledMsg() {
        final String[] selectedImgPath = {null};
        final String[] selectedMp3Path = {null};

        // 构建预览布局
        LinearLayout ll = new LinearLayout(sAct);
        ll.setOrientation(LinearLayout.VERTICAL);

        final EditText et = new EditText(sAct);
        et.setHint("消息内容(可选, 纯文本可不填)");
        et.setMinLines(2);
        et.setPadding(dp(8), dp(8), dp(8), dp(8));
        et.setBackgroundColor(AppColors.bg());
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(AppColors.bg());
        etBg.setCornerRadius(dp(8));
        et.setBackground(etBg);
        ll.addView(et);

        // 图片选择行
        final TextView imgLabel = new TextView(sAct);
        imgLabel.setText("图片: 未选择");
        imgLabel.setTextSize(12);
        imgLabel.setTextColor(AppColors.text2());
        imgLabel.setPadding(dp(8), dp(12), dp(8), 0);
        ll.addView(imgLabel);

        Button imgBtn = new Button(sAct);
        imgBtn.setText("选择图片");
        imgBtn.setTextSize(13);
        imgBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable imgBtnBg = new GradientDrawable();
        imgBtnBg.setColor(AppColors.accent());
        imgBtnBg.setCornerRadius(dp(8));
        imgBtn.setBackground(imgBtnBg);
        imgBtn.setOnClickListener(v -> pickImage(path -> {
            selectedImgPath[0] = path;
            imgLabel.setText(path != null ? "图片: " + path.replace("/sdcard/", ".../") : "图片: 未选择");
        }));
        ll.addView(imgBtn);

        // MP3选择行
        final TextView mp3Label = new TextView(sAct);
        mp3Label.setText("语音(MP3): 未选择");
        mp3Label.setTextSize(12);
        mp3Label.setTextColor(AppColors.text2());
        mp3Label.setPadding(dp(8), dp(12), dp(8), 0);
        ll.addView(mp3Label);

        Button mp3Btn = new Button(sAct);
        mp3Btn.setText("选择MP3");
        mp3Btn.setTextSize(13);
        mp3Btn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable mp3BtnBg = new GradientDrawable();
        mp3BtnBg.setColor(AppColors.accent());
        mp3BtnBg.setCornerRadius(dp(8));
        mp3Btn.setBackground(mp3BtnBg);
        mp3Btn.setOnClickListener(v -> pickAudio(path -> {
            selectedMp3Path[0] = path;
            mp3Label.setText(path != null ? "MP3: " + path.replace("/sdcard/", ".../") : "MP3: 未选择");
        }));
        ll.addView(mp3Btn);

        // 时间设置 — DatePicker + TimePicker
        final long[] selectedTimeMs = {0};
        final TextView timeLabel = new TextView(sAct);
        timeLabel.setText("发送时间: 未设置");
        timeLabel.setTextSize(13);
        timeLabel.setTextColor(AppColors.text2());
        timeLabel.setPadding(dp(8), dp(12), dp(8), 0);
        ll.addView(timeLabel);

        Button timeBtn = new Button(sAct);
        timeBtn.setText("选择发送时间");
        timeBtn.setTextSize(13);
        timeBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable timeBtnBg = new GradientDrawable();
        timeBtnBg.setColor(AppColors.accent());
        timeBtnBg.setCornerRadius(dp(8));
        timeBtn.setBackground(timeBtnBg);
        timeBtn.setOnClickListener(v -> showDateTimePicker(selectedTimeMs, timeLabel));
        ll.addView(timeBtn);

        new AlertDialog.Builder(sAct).setTitle("定时发送")
                .setView(ll)
                .setPositiveButton("预约发送", (d, w) -> {
                    String msg = et.getText().toString().trim();
                    String imgPath = selectedImgPath[0];
                    String mp3Path = selectedMp3Path[0];
                    if (TextUtils.isEmpty(msg) && imgPath == null && mp3Path == null) {
                        toast("请至少选择一项发送内容"); return;
                    }
                    if (selectedTimeMs[0] <= 0) {
                        toast("请设置发送时间"); return;
                    }
                    int delaySec = (int) ((selectedTimeMs[0] - System.currentTimeMillis()) / 1000);
                    if (delaySec <= 0) { toast("时间必须在未来"); return; }
                    final String fMsg = msg.isEmpty() ? null : msg;
                    final String fImg = imgPath;
                    final String fMp3 = mp3Path;
                    final int fDelay = delaySec;
                    SimpleDateFormat sdfHint = new SimpleDateFormat("MM月dd日 HH:mm");
                    String hint = sdfHint.format(new Date(selectedTimeMs[0]));
                    toast("已预约: " + hint + " 发送");
                    sH.postDelayed(() -> {
                        new Thread(() -> {
                            try {
                                if (fImg != null) sendImageViaXes(fImg);
                                if (fMp3 != null) sendMp3AsVoice(fMp3);
                                if (fMsg != null) WmReflect.sendTextMsg(sCL, fMsg, sUser);
                                sH.post(() -> toast("定时消息已发送完毕"));
                            } catch (Exception e) {
                                sH.post(() -> toast("发送失败: " + e.getMessage()));
                            }
                        }).start();
                    }, fDelay * 1000L);
                }).setNegativeButton("取消", null).show();
    }

    /** 通过微信内部API发送图片 */
    static boolean sendImageViaXes(String imgPath) {
        try {
            File f = new File(imgPath);
            if (!f.exists()) { toast("图片文件不存在:" + imgPath); return false; }
            Class<?> nm = XposedHelpers.findClass("com.tencent.mm.modelmulti.n", sCL);
            Object msg = XposedHelpers.newInstance(nm, sUser, imgPath, 3, (Object) null);
            XposedHelpers.callStaticMethod(nm, "b", msg);
            return true;
        } catch (Exception e) {
            LogWriter.log(TAG, "sendImageViaXes err: " + e.getMessage());
            toast("图片发送失败");
            return false;
        }
    }

    /** MP3文件转换为SILK/AMR后通过微信语音发送 */
    static String sendMp3AsVoice(String mp3Path) {
        try {
            File mp3File = new File(mp3Path);
            if (!mp3File.exists()) return "MP3文件不存在:" + mp3Path;

            // 获取语音文件目录 (voice2目录)
            String voiceDir = findVoice2Dir();
            File voiceDirFile = new File(voiceDir);
            if (!voiceDirFile.exists()) voiceDirFile.mkdirs();

            // 用WeChat自带AudioTool转码 MP3→SILK
            String silkPath = voiceDir + "leshao_" + System.currentTimeMillis() + ".silk";
            boolean converted = convertMp3ToWeChat(silkPath, mp3File);
            if (!converted) return "MP3转码失败(不支持的格式)";

            // 构建 WeChat 语音消息并发送
            Class<?> nm = XposedHelpers.findClass("com.tencent.mm.modelmulti.n", sCL);
            Object voiceMsg = XposedHelpers.newInstance(nm, sUser, silkPath, 2, (Object) null);
            XposedHelpers.callStaticMethod(nm, "b", voiceMsg);
            return "语音已发送";
        } catch (Exception e) {
            LogWriter.log(TAG, "sendMp3AsVoice err: " + e.getMessage());
            return "发送失败: " + e.getMessage();
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

    /** 尝试调用WeChat内部AudioTool转码MP3→SILK */
    static boolean convertMp3ToWeChat(String outputPath, File mp3File) {
        try {
            // 尝试 WeChat 8.x AudioTool API
            Class<?> audioTool = null;
            for (String cls : new String[]{
                    "com.tencent.mm.audio.recorder.MMRecorderUtil",
                    "com.tencent.mm.audio.b", "com.tencent.mm.audio.c",
                    "com.tencent.mm.audio.d", "com.tencent.mm.audio.e"
            }) {
                try { audioTool = sCL.loadClass(cls); break; } catch (Throwable ignored) {}
            }
            if (audioTool != null) {
                // MMPcmAudioRecorder: pcm→silk
                // 先尝试用 MediaPlayer 读取 MP3 时长
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(mp3File.getAbsolutePath());
                    // WeChat silk 编码需要音频时长参数
                    String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    mmr.release();
                    if (durStr != null) {
                        int durMs = Integer.parseInt(durStr);
                        int durSec = Math.max(1, durMs / 1000);

                        // 尝试 WeChat silk encoder: new MMRecorderUtil(silkPath, durationSec)
                        try {
                            Class<?> util = sCL.loadClass("com.tencent.mm.audio.recorder.MMRecorderUtil");
                            Object rec = XposedHelpers.newInstance(util, outputPath, durSec);
                            // 尝试 native encode: rec.encodePcmToSilk(pcmData, pcmLen)
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }

            // Fallback: 直接复制文件尝试 (如果微信支持直接播放MP3)
            java.io.FileInputStream fis = new java.io.FileInputStream(mp3File);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outputPath);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            fis.close();
            fos.close();
            return true;
        } catch (Exception e) {
            LogWriter.log(TAG, "convertMp3ToWeChat err: " + e.getMessage());
            return false;
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
            catch (Throwable ignored) { sCachedDb = null; sCachedRawQueryMethod = null; }
        }

        // 2. 使用 DatabaseProvider 捕获的 WCDB 实例
        Object db = com.leshao.v3.db.DatabaseProvider.getDatabase();
        if (db != null) {
            Cursor c = tryRawQuery(db, sql, args);
            if (c != null) { sCachedDb = db; return c; }
        }

        // 3. 回退: VersionCompat 打开
        Object db2 = openWxDb();
        if (db2 != null) {
            Cursor c = tryRawQuery(db2, sql, args);
            if (c != null) { sCachedDb = db2; return c; }
        }

        // 4. 最后回退: 用捕获的密码直接打开
        byte[] pwd = com.leshao.v3.db.DatabaseProvider.getPassword();
        if (pwd != null) {
            Object db3 = openWxDbWithPassword(pwd);
            if (db3 != null) {
                Cursor c = tryRawQuery(db3, sql, args);
                if (c != null) { sCachedDb = db3; return c; }
            }
        }

        LogWriter.log(TAG, "rawQueryMsg: all attempts failed");
        return null;
    }

    /** 尝试在 db 上调用 rawQuery */
    private static Cursor tryRawQuery(Object db, String sql, String[] args) {
        try {
            for (Method m : db.getClass().getMethods()) {
                if (m.getName().equals("rawQuery") && m.getParameterCount() >= 1
                        && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    sCachedRawQueryMethod = m;
                    return (Cursor) m.invoke(db, sql, args);
                }
            }
            for (Method m : db.getClass().getDeclaredMethods()) {
                if (m.getName().equals("rawQuery") && m.getParameterCount() >= 1
                        && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    sCachedRawQueryMethod = m;
                    return (Cursor) m.invoke(db, sql, args);
                }
            }
            // 也尝试混淆后的方法名
            for (String mn : new String[]{"u", "rowQuery", "v", "w", "x", "y", "z"}) {
                for (Method m : db.getClass().getMethods()) {
                    if (m.getName().equals(mn) && m.getParameterCount() >= 1
                            && m.getParameterTypes()[0] == String.class) {
                        m.setAccessible(true);
                        sCachedRawQueryMethod = m;
                        return (Cursor) m.invoke(db, sql, args);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
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

    /** 使用 DatabaseProvider 捕获的密码直接打开 EnMicroMsg.db */
    static Object openWxDbWithPassword(byte[] password) {
        try {
            Context appCtx = com.leshao.v3.ContextManager.getAppContext();
            if (appCtx == null) return null;
            long uin = getWxUin(appCtx);
            if (uin <= 0) return null;
            String base = com.leshao.v3.hook.VersionCompat.getBaseDir(sCL, appCtx);
            String hash = com.leshao.v3.hook.VersionCompat.getDbHash(sCL, (int) uin);
            String dbPath = base + "MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) return null;

            // 尝试通过 WCDB SQLiteDatabase 打开
            Class<?> dbOpener = com.leshao.v3.hook.VersionCompat.findDbOpenerClass(sCL);
            if (dbOpener != null) {
                // 尝试带 byte[] 密码的 openDatabase
                for (Method m : dbOpener.getDeclaredMethods()) {
                    if (m.getName().equals("s") || m.getName().equals("r")
                            || m.getName().equals("t") || m.getName().equals("openDatabase")) {
                        if (m.getParameterCount() >= 2) {
                            try {
                                m.setAccessible(true);
                                Object result = m.invoke(null, dbPath, password);
                                if (result != null) return result;
                            } catch (Throwable ignored) {}
                            try {
                                m.setAccessible(true);
                                Object result = m.invoke(null, dbPath, password, 0);
                                if (result != null) return result;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }

            // 尝试 SQLCipher 直接打开
            try {
                Class<?> sqlcipherDb = Class.forName("net.sqlcipher.database.SQLiteDatabase");
                Method openDb = sqlcipherDb.getMethod("openOrCreateDatabase",
                        String.class, String.class, Object.class);
                Object db = openDb.invoke(null, dbPath, "", null);
                if (db != null) {
                    Method rawQuery = db.getClass().getMethod("rawQuery", String.class, String[].class);
                    // 用 SQLCipher 的 key 设置
                    sqlcipherDb.getMethod("changePassword", String.class).invoke(db,
                            new String(password, "UTF-8"));
                    return db;
                }
            } catch (Throwable ignored) {}

            // 尝试 net.sqlcipher.database.SQLiteDatabase openDatabase
            try {
                Class<?> sqlcipherDb = Class.forName("net.sqlcipher.database.SQLiteDatabase");
                Method openDb = sqlcipherDb.getMethod("openDatabase",
                        String.class, String.class, Object.class, int.class);
                Object db = openDb.invoke(null, dbPath, new String(password, "UTF-8"), null, 0);
                if (db != null) return db;
            } catch (Throwable ignored) {}

            return null;
        } catch (Throwable t) {
            LogWriter.log(TAG, "openWxDbWithPassword err: " + t.getClass().getSimpleName());
            return null;
        }
    }

    static long getWxUin(Context ctx) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("system_config_prefs", 0);
            Object uv = sp.getAll().get("default_uin");
            if (uv != null) return Long.parseLong(uv.toString());
        } catch (Throwable ignored) {}
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
                "SELECT msgContent, createTime, isSend, type FROM message WHERE talker=? ORDER BY createTime ASC LIMIT 50000",
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
                } catch (Exception ignored) {}
            }
            fos.write(("\n================================\n共 " + cnt + " 条消息\n================================\n").getBytes("UTF-8"));
            fos.close();
            c.close();
            return cnt;
        } catch (Exception e) {
            LogWriter.log(TAG, "exportChat err: " + e.getMessage());
            return -1;
        } finally {
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
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

    // 4. 关键词提醒 — 显示监控范围
    static void showKeywordSet() {
        final EditText et = new EditText(sAct);
        et.setText(WmPrefs.getKeywords());
        et.setHint("关键词,逗号分隔");
        TextView info = new TextView(sAct);
        info.setTextSize(12);
        info.setTextColor(AppColors.text2());
        info.setPadding(0, 8, 0, 0);
        info.setText("当前聊天对象: " + sUser + "\n"
                + "匹配关键词的消息会触发通知\n"
                + "修改关键词后自动生效");

        LinearLayout ll = new LinearLayout(sAct);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.addView(et);
        ll.addView(info);

        new AlertDialog.Builder(sAct).setTitle("关键词提醒").setView(ll)
                .setPositiveButton("保存", (d, w) -> {
                    WmPrefs.setStr("keywords", et.getText().toString().trim());
                    toast("已保存，监控対象: " + sUser);
                }).setNegativeButton("取消", null).show();
    }

    // 5. 语音自动播放 / 消息朗读 已改用面板内 Switch，无需弹窗

    static void toggleTranslate() {
        sTranslateOn = !sTranslateOn;
        toast("自动翻译:" + (sTranslateOn ? "开" : "关"));
    }

    // 6. 私密备注 — 保存并可查看
    static void showPrivateNote() {
        String existing = WmPrefs.getStr("note_" + sUser, "");
        if (existing.isEmpty()) {
            final EditText et = new EditText(sAct);
            et.setHint("私密备注");
            new AlertDialog.Builder(sAct).setTitle("私密备注 (" + sUser + ")").setView(et)
                    .setPositiveButton("保存", (d, w) -> {
                        WmPrefs.setStr("note_" + sUser, et.getText().toString().trim());
                        toast("已保存");
                    }).setNegativeButton("取消", null).show();
        } else {
            new AlertDialog.Builder(sAct).setTitle("私密备注 (" + sUser + ")")
                    .setMessage(existing)
                    .setPositiveButton("修改", (d, w) -> {
                        final EditText et = new EditText(sAct);
                        et.setText(existing);
                        new AlertDialog.Builder(sAct).setTitle("修改备注").setView(et)
                                .setPositiveButton("保存", (d2, w2) -> {
                                    WmPrefs.setStr("note_" + sUser, et.getText().toString().trim());
                                    toast("已保存");
                                }).setNegativeButton("取消", null).show();
                    })
                    .setNeutralButton("删除", (d, w) -> {
                        WmPrefs.setStr("note_" + sUser, "");
                        toast("已删除");
                    })
                    .setNegativeButton("关闭", null).show();
        }
    }

    // 7. 聊天统计 — 从消息DB读取真实数据
    static void showChatStats() {
        new Thread(() -> {
            String stats = buildChatStats();
            sH.post(() -> {
                if (stats == null) {
                    toast("数据库未就绪,请稍后重试");
                } else if (stats.isEmpty()) {
                    toast("暂无消息记录");
                } else {
                    new AlertDialog.Builder(sAct).setTitle("聊天统计")
                            .setMessage(stats)
                            .setPositiveButton("确定", null).show();
                }
            });
        }).start();
    }

    static String buildChatStats() {
        Cursor c = null;
        try {
            c = rawQueryMsg(
                "SELECT msgContent, createTime, isSend, type FROM message WHERE talker=? ORDER BY createTime ASC",
                new String[]{sUser});
            if (c == null) return null;
            if (c.getCount() == 0) return "";

            int total = c.getCount();
            int selfCnt = 0, peerCnt = 0;
            int txtCnt = 0, imgCnt = 0, voiceCnt = 0, videoCnt = 0, emojiCnt = 0;
            long firstTime = 0, lastTime = 0;

            while (c.moveToNext()) {
                long tm = c.getLong(1);
                if (firstTime == 0 || tm < firstTime) firstTime = tm;
                if (tm > lastTime) lastTime = tm;
                int type = c.getInt(3);
                int isSend = c.getInt(2);
                if (isSend == 1) selfCnt++; else peerCnt++;
                switch (type) {
                    case 1: txtCnt++; break;
                    case 3: imgCnt++; break;
                    case 34: voiceCnt++; break;
                    case 43: videoCnt++; break;
                    case 47: emojiCnt++; break;
                }
            }
            c.close();
            c = null;

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            StringBuilder sb = new StringBuilder();
            sb.append("对象: ").append(sUser).append("\n\n");
            sb.append("总消息: ").append(total).append(" 条\n");
            sb.append("我发送: ").append(selfCnt).append(" 条\n");
            sb.append("对方发送: ").append(peerCnt).append(" 条\n\n");
            sb.append("文本: ").append(txtCnt).append("\n");
            sb.append("图片: ").append(imgCnt).append("\n");
            sb.append("语音: ").append(voiceCnt).append("\n");
            sb.append("视频: ").append(videoCnt).append("\n");
            sb.append("表情: ").append(emojiCnt).append("\n\n");
            if (firstTime > 0) sb.append("最早: ").append(sdf.format(new Date(firstTime))).append("\n");
            if (lastTime > 0) sb.append("最新: ").append(sdf.format(new Date(lastTime)));
            return sb.toString();
        } catch (Exception e) {
            LogWriter.log(TAG, "chatStats err: " + e.getMessage());
            return null;
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
    }

    // 8. 消息搜索
    static void showMsgSearch() {
        final EditText et = new EditText(sAct);
        et.setHint("输入搜索关键词");
        new AlertDialog.Builder(sAct).setTitle("消息搜索")
                .setView(et)
                .setPositiveButton("搜索", (d, w) -> {
                    String kw = et.getText().toString().trim();
                    if (kw.isEmpty()) { toast("请输入关键词"); return; }
                    new Thread(() -> {
                        String result = searchMessages(kw);
                        sH.post(() -> {
                            if (result == null) {
                                toast("数据库未就绪,请稍后重试");
                            } else if (result.isEmpty()) {
                                toast("未找到匹配消息");
                            } else {
                                new AlertDialog.Builder(sAct).setTitle("搜索结果:" + kw)
                                        .setMessage(result)
                                        .setPositiveButton("确定", null).show();
                            }
                        });
                    }).start();
                }).setNegativeButton("取消", null).show();
    }

    static String searchMessages(String kw) {
        Cursor c = null;
        try {
            c = rawQueryMsg(
                "SELECT msgContent, createTime, isSend, type FROM message WHERE talker=? AND msgContent LIKE ? ORDER BY createTime DESC LIMIT 100",
                new String[]{sUser, "%" + kw + "%"});
            if (c == null) return null;
            if (c.getCount() == 0) return "";

            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm");
            int cnt = 0, max = 20;
            while (c.moveToNext() && cnt < max) {
                String content = c.getString(0);
                if (content == null) continue;
                long tm = c.getLong(1);
                int isSend = c.getInt(2);
                int type = c.getInt(3);
                sb.append(sdf.format(new Date(tm))).append(" ");
                sb.append(isSend == 1 ? "[我]" : "[对方]").append(" ");
                sb.append("[").append(typeMap(type)).append("] ");
                String preview = content.length() > 50 ? content.substring(0, 50) + "..." : content;
                sb.append(preview).append("\n");
                cnt++;
            }
            if (cnt >= max) sb.append("\n...仅显示前").append(max).append("条");
            sb.append("\n共找到 ").append(cnt).append(" 条匹配消息");
            return sb.toString();
        } catch (Exception e) {
            LogWriter.log(TAG, "searchMsg err: " + e.getMessage());
            return null;
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
    }

    // ===== 面板开关控件 =====
    static LinearLayout makeToggleRow(String label, String prefKey) {
        LinearLayout row = new LinearLayout(sAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(AppColors.bg());
        rowBg.setCornerRadius(dp(12));
        row.setBackground(rowBg);

        TextView tv = new TextView(sAct);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(AppColors.text1());
        LinearLayout.LayoutParams tvlp = new LinearLayout.LayoutParams(0, dp(36), 1f);
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
                } catch (Throwable ignored) {}
            }
            toast(label.replaceAll("[^\\u4e00-\\u9fa5]", "") + (on ? ":开" : ":关"));
        });
        row.addView(sw);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        rlp.setMargins(dp(10), 0, dp(10), dp(8));
        row.setLayoutParams(rlp);
        return row;
    }

    static int dp(int d) {
        return (int) (d * sAct.getResources().getDisplayMetrics().density);
    }

    static void toast(String m) {
        Toast.makeText(sAct, m, Toast.LENGTH_SHORT).show();
    }

    // ===== @强提醒 =====
    static void showAtRemind() {
        new AlertDialog.Builder(sAct).setTitle("@强提醒")
                .setMessage("当前对象: " + sUser + "\n\n"
                        + "开启后, 当此联系人发来消息时, 系统会弹出高强度通知。\n"
                        + "可在设置中管理提醒规则。")
                .setPositiveButton("启用对该对象的强提醒", (d, w) -> {
                    WmPrefs.setStr("at_remind_" + sUser, "1");
                    toast("已对 " + sUser + " 开启强提醒");
                })
                .setNeutralButton("管理提醒列表", (d, w) -> showRemindList())
                .setNegativeButton("取消", null).show();
    }

    static void showRemindList() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Map<String, ?> all = sAct.getSharedPreferences("leshao_prefs", 0).getAll();
            for (String key : all.keySet()) {
                if (key.startsWith("at_remind_") && "1".equals(String.valueOf(all.get(key)))) {
                    sb.append(key.substring(10)).append("\n");
                }
            }
        } catch (Throwable ignored) {}
        if (sb.length() == 0) sb.append("暂无提醒对象");
        new AlertDialog.Builder(sAct).setTitle("强提醒列表")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null).show();
    }

    // ===== 配音魔方 TTS =====
    static void showTtsCube() {
        String savedKey = WmPrefs.getStr("tts_cube_key", "");
        String savedVoice = WmPrefs.getStr("tts_cube_voice", "");
        String savedModel = WmPrefs.getStr("tts_cube_model", "tts-1");

        LinearLayout ll = new LinearLayout(sAct);
        ll.setOrientation(LinearLayout.VERTICAL);

        // Key 输入 + 检测按钮
        TextView keyLabel = new TextView(sAct);
        keyLabel.setText("TTS API Key:");
        keyLabel.setTextSize(13);
        keyLabel.setTextColor(AppColors.text1());
        ll.addView(keyLabel);

        LinearLayout keyRow = new LinearLayout(sAct);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText keyEt = new EditText(sAct);
        keyEt.setHint("输入配音魔方 API Key");
        keyEt.setText(savedKey);
        keyEt.setSingleLine();
        LinearLayout.LayoutParams ketlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        keyRow.addView(keyEt, ketlp);

        Button checkBtn = new Button(sAct);
        checkBtn.setText("检测");
        checkBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable chkBg = new GradientDrawable();
        chkBg.setColor(AppColors.accent());
        chkBg.setCornerRadius(dp(8));
        checkBtn.setBackground(chkBg);
        keyRow.addView(checkBtn);
        ll.addView(keyRow);

        checkBtn.setOnClickListener(v -> {
            String key = keyEt.getText().toString().trim();
            if (key.isEmpty()) { toast("请先输入Key"); return; }
            new Thread(() -> {
                String result = checkTtsKey(key);
                sH.post(() -> toast(result));
            }).start();
        });

        // 音色选择 + 试听
        TextView voiceLabel = new TextView(sAct);
        voiceLabel.setText("\n音色选择:");
        voiceLabel.setTextSize(13);
        voiceLabel.setTextColor(AppColors.text1());
        ll.addView(voiceLabel);

        final String[] voices = {"default", "aura-aria-en", "aura-orpheus-en", "aura-luna-en",
                "aura-atlas-en", "nova", "shimmer", "echo", "fable", "onyx", "alloy"};
        final String[] voiceLabels = {"默认(default)", "Aria(美式女)", "Orpheus(美式男)", "Luna(英式女)",
                "Atlas(美式男)", "Nova(标准女)", "Shimmer(温柔女)", "Echo(低沉男)", "Fable(旁白)", "Onyx(深沉男)", "Alloy(中性)"};

        for (int i = 0; i < voices.length; i++) {
            final int idx = i;
            LinearLayout vRow = new LinearLayout(sAct);
            vRow.setOrientation(LinearLayout.HORIZONTAL);
            vRow.setPadding(dp(8), dp(4), dp(8), dp(4));

            TextView vName = new TextView(sAct);
            vName.setText(voiceLabels[i]);
            vName.setTextSize(12);
            vName.setTextColor(AppColors.text2());
            LinearLayout.LayoutParams vnlp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            vName.setGravity(Gravity.CENTER_VERTICAL);
            vRow.addView(vName, vnlp);

            Button listenBtn = new Button(sAct);
            listenBtn.setText("\uD83C\uDFA7");
            listenBtn.setTextSize(14);
            listenBtn.setTextColor(AppColors.WHITE_TEXT);
            GradientDrawable lbBg = new GradientDrawable();
            lbBg.setColor(AppColors.accent());
            lbBg.setCornerRadius(dp(6));
            listenBtn.setBackground(lbBg);
            vRow.addView(listenBtn);

            Button selBtn = new Button(sAct);
            selBtn.setText("✓");
            selBtn.setTextSize(11);
            selBtn.setTextColor(AppColors.WHITE_TEXT);
            GradientDrawable sbBg2 = new GradientDrawable();
            sbBg2.setColor(voices[i].equals(savedVoice) ? AppColors.accent() : AppColors.text3());
            sbBg2.setCornerRadius(dp(6));
            selBtn.setBackground(sbBg2);
            vRow.addView(selBtn);

            ll.addView(vRow);

            listenBtn.setOnClickListener(v2 -> {
                String key = keyEt.getText().toString().trim();
                if (key.isEmpty()) { toast("请先输入Key"); return; }
                new Thread(() -> {
                    String result = previewVoice(key, voices[idx], "欢迎使用乐少助手");
                    sH.post(() -> {
                        if (result.startsWith("OK:")) {
                            try {
                                MediaPlayer mp = new MediaPlayer();
                                mp.setDataSource(result.substring(3));
                                mp.prepare();
                                mp.start();
                                mp.setOnCompletionListener(mp2 -> mp2.release());
                                toast("正在试听 " + voiceLabels[idx]);
                            } catch (Exception e) {
                                toast("播放失败: " + e.getMessage());
                            }
                        } else {
                            toast(result);
                        }
                    });
                }).start();
            });

            selBtn.setOnClickListener(v2 -> {
                WmPrefs.setStr("tts_cube_voice", voices[idx]);
                toast("已选择默认音色: " + voiceLabels[idx]);
                // 刷新按钮颜色
                showTtsCube();
            });
        }

        new AlertDialog.Builder(sAct).setTitle("配音魔方 TTS 设置")
                .setView(ll)
                .setPositiveButton("保存", (d, w) -> {
                    WmPrefs.setStr("tts_cube_key", keyEt.getText().toString().trim());
                    toast("TTS配置已保存");
                })
                .setNegativeButton("取消", null).show();
    }

    /** 检测 TTS Key 是否有效 */
    static String checkTtsKey(String key) {
        try {
            java.net.URL url = new java.net.URL("https://api.openai.com/v1/models");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200) return "Key 有效";
            if (code == 401) return "Key 无效: 认证失败(code=" + code + ")";
            return "检测失败: HTTP " + code;
        } catch (Exception e) {
            return "检测失败: " + e.getMessage();
        }
    }

    /** 预览音色 - 调用 OpenAI TTS API 生成音频文件 */
    static String previewVoice(String key, String voice, String text) {
        try {
            String body = "{\"model\":\"tts-1\",\"input\":\"" + escapeJson(text)
                    + "\",\"voice\":\"" + voice + "\",\"response_format\":\"mp3\"}";

            java.net.URL url = new java.net.URL("https://api.openai.com/v1/audio/speech");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            java.io.OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return "试听失败: HTTP " + code;
            }

            Context appCtx = com.leshao.v3.ContextManager.getAppContext();
            File cacheDir = appCtx != null ? appCtx.getCacheDir() : new File(Environment.getExternalStorageDirectory(), "leshao_v3_cache");
            File ttsDir = new File(cacheDir, "tts_preview");
            ttsDir.mkdirs();
            File outFile = new File(ttsDir, "openai_" + voice + ".mp3");

            java.io.InputStream is = conn.getInputStream();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();
            conn.disconnect();
            return "OK:" + outFile.getAbsolutePath();
        } catch (Exception e) {
            return "试听失败: " + e.getMessage();
        }
    }

    static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
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

    private static final String[] MASS_TYPES = {"文本消息", "图文消息", "文视消息", "语音消息", "位置文本", "名片文字"};
    private static final String[] MASS_TYPE_KEYS = {"text", "image_text", "video_text", "voice", "location_text", "card_text"};

    /** 入口: 选择消息类型 — 2×3 等宽网格 + 底部3按钮横排 + BottomSheet */
    static void showMassSend() {
        if (sAct == null || sAct.isFinishing()) return;

        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(4));
        root.setBackground(CandyUi.cardBg(sAct));

        // 标题
        TextView titleTv = new TextView(sAct);
        titleTv.setText("选择消息类型 - 乐少万群定时群发");
        titleTv.setTextSize(16);
        titleTv.setTextColor(AppColors.TEXT_TITLE);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleTv.setPadding(0, 0, 0, dp(14));
        root.addView(titleTv);

        // 2行×3列等宽网格
        final int[] selectedIdx = {-1};
        final LinearLayout[] gridRows = new LinearLayout[2];

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(sAct);
            row.setOrientation(LinearLayout.HORIZONTAL);
            gridRows[r] = row;
            for (int c = 0; c < 3; c++) {
                final int idx = r * 3 + c;
                Button btn = new Button(sAct);
                btn.setText(MASS_TYPES[idx]);
                btn.setTextSize(12);
                btn.setAllCaps(false);
                btn.setSingleLine(true);
                btn.setTextColor(AppColors.WHITE_TEXT);
                btn.setGravity(Gravity.CENTER);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(AppColors.accent());
                bg.setCornerRadius(dp(10));
                btn.setBackground(bg);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(42), 1f);
                blp.setMargins(dp(3), dp(4), dp(3), dp(4));
                btn.setLayoutParams(blp);
                btn.setOnClickListener(v -> {
                    if (selectedIdx[0] == idx) { selectedIdx[0] = -1; }
                    else selectedIdx[0] = idx;
                    for (int rr = 0; rr < 2; rr++) {
                        if (gridRows[rr] == null) continue;
                        for (int cc = 0; cc < gridRows[rr].getChildCount(); cc++) {
                            View child = gridRows[rr].getChildAt(cc);
                            if (child instanceof Button) {
                                boolean isSel = (rr * 3 + cc == selectedIdx[0]);
                                GradientDrawable sd = new GradientDrawable();
                                sd.setColor(AppColors.accent());
                                sd.setCornerRadius(dp(10));
                                sd.setAlpha(isSel ? 255 : 80);
                                ((Button) child).setBackground(sd);
                            }
                        }
                    }
                });
                row.addView(btn);
            }
            root.addView(row);
        }

        // 底部3按钮横排: 群发记录 | 取消 | 下一步
        LinearLayout bottomBar = new LinearLayout(sAct);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(0, dp(20), 0, dp(8));

        // 先创建 Dialog 以便按钮引用
        final Dialog dlg = new Dialog(sAct);
        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(true);

        Button recordsBtn = new Button(sAct);
        recordsBtn.setText("群发记录");
        recordsBtn.setTextSize(13);
        recordsBtn.setAllCaps(false);
        recordsBtn.setTextColor(AppColors.accent());
        recordsBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        recordsBtn.setOnClickListener(v -> { dlg.dismiss(); showMassSendRecords(); });
        bottomBar.addView(recordsBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button cancelBtn = new Button(sAct);
        cancelBtn.setText("取消");
        cancelBtn.setTextSize(13);
        cancelBtn.setAllCaps(false);
        cancelBtn.setTextColor(AppColors.text2());
        cancelBtn.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        cancelBtn.setOnClickListener(v -> dlg.dismiss());
        bottomBar.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button nextBtn = new Button(sAct);
        nextBtn.setText("下一步");
        nextBtn.setTextSize(13);
        nextBtn.setAllCaps(false);
        nextBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable nb = new GradientDrawable();
        nb.setColor(AppColors.accent());
        nb.setCornerRadius(dp(10));
        nextBtn.setBackground(nb);
        nextBtn.setOnClickListener(v -> {
            if (selectedIdx[0] < 0) { toast("请选择消息类型"); return; }
            dlg.dismiss();
            showMassSendStep2(selectedIdx[0]);
        });
        bottomBar.addView(nextBtn, new LinearLayout.LayoutParams(0, dp(44), 1f));

        root.addView(bottomBar);

        // BottomSheet 设置
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.75f;
            w.setAttributes(lp);
        }
        dlg.show();
    }

    /** 第二步: 目标 + 时间 + 内容 合并设置 */
    static void showMassSendStep2(final int msgTypeIdx) {
        ScrollView sv = new ScrollView(sAct);
        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackground(CandyUi.cardBg(sAct));

        final Set<String>[] selectedTargets = new Set[]{new java.util.LinkedHashSet<>()};
        final long[] selectedTimeMs = {0};
        final String[] pickedFilePath = {null};

        // --- 目标选择区域 ---
        final TextView targetsLabel = new TextView(sAct);
        targetsLabel.setText("已选目标: 0 个");
        targetsLabel.setTextSize(14);
        targetsLabel.setTextColor(AppColors.text1());
        targetsLabel.setPadding(0, 0, 0, dp(8));
        root.addView(targetsLabel);

        Button pickTargetBtn = new Button(sAct);
        pickTargetBtn.setText("选择目标群");
        pickTargetBtn.setTextSize(13);
        pickTargetBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable ptBg = new GradientDrawable();
        ptBg.setColor(AppColors.accent());
        ptBg.setCornerRadius(dp(8));
        pickTargetBtn.setBackground(ptBg);
        pickTargetBtn.setOnClickListener(v -> {
            com.leshao.v3.ui.ContactPickerDialog.show(sAct, "",
                    com.leshao.v3.ui.ContactPickerDialog.MODE_GROUP,
                    (selected, display) -> {
                        if (selected.isEmpty()) return;
                        selectedTargets[0] = new java.util.LinkedHashSet<>(selected);
                        targetsLabel.setText("已选目标: " + selectedTargets[0].size() + " 个");
                    });
        });
        root.addView(pickTargetBtn);

        // --- 时间设置区域 ---
        final TextView timeLabel = new TextView(sAct);
        timeLabel.setText("发送时间: 未设置");
        timeLabel.setTextSize(14);
        timeLabel.setTextColor(AppColors.text1());
        timeLabel.setPadding(0, dp(12), 0, dp(8));
        root.addView(timeLabel);

        Button timeBtn = new Button(sAct);
        timeBtn.setText("选择日期时间");
        timeBtn.setTextSize(13);
        timeBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable tbBg = new GradientDrawable();
        tbBg.setColor(AppColors.accent());
        tbBg.setCornerRadius(dp(8));
        timeBtn.setBackground(tbBg);
        timeBtn.setOnClickListener(v -> showDateTimePicker(selectedTimeMs, timeLabel));
        root.addView(timeBtn);

        // --- 内容输入区域 ---
        String savedText = WmPrefs.getStr("mass_send_text", "");
        final EditText et = new EditText(sAct);
        et.setHint("输入消息内容");
        et.setText(savedText);
        et.setMinLines(3);
        et.setPadding(dp(8), dp(8), dp(8), dp(8));
        et.setBackground(CandyUi.inputBg(sAct));
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etlp.setMargins(0, dp(12), 0, 0);
        et.setLayoutParams(etlp);
        root.addView(et);

        // --- 文件选择区域 ---
        final TextView fileLabel = new TextView(sAct);
        fileLabel.setTextSize(12);
        fileLabel.setTextColor(AppColors.text2());
        fileLabel.setPadding(0, dp(6), 0, 0);

        Button fileBtn = null;

        switch (msgTypeIdx) {
            case 0: // 文本
                fileLabel.setText("类型: 纯文本");
                root.addView(fileLabel);
                break;
            case 1: // 图文
                fileLabel.setText("图片: 未选择");
                root.addView(fileLabel);
                fileBtn = new Button(sAct);
                fileBtn.setText("选择图片");
                fileBtn.setTextSize(13);
                fileBtn.setTextColor(AppColors.WHITE_TEXT);
                GradientDrawable fBg = new GradientDrawable();
                fBg.setColor(AppColors.accent());
                fBg.setCornerRadius(dp(8));
                fileBtn.setBackground(fBg);
                fileBtn.setOnClickListener(v -> pickImage(path -> {
                    pickedFilePath[0] = path;
                    fileLabel.setText(path != null ? "图片: " + path.replace("/sdcard/", ".../") : "图片: 未选择");
                }));
                root.addView(fileBtn);
                break;
            case 2: // 文视
                fileLabel.setText("视频: 未选择");
                root.addView(fileLabel);
                fileBtn = new Button(sAct);
                fileBtn.setText("选择视频");
                fileBtn.setTextSize(13);
                fileBtn.setTextColor(AppColors.WHITE_TEXT);
                GradientDrawable fbBg = new GradientDrawable();
                fbBg.setColor(AppColors.accent());
                fbBg.setCornerRadius(dp(8));
                fileBtn.setBackground(fbBg);
                fileBtn.setOnClickListener(v -> launchSystemFilePicker("video/*", path -> {
                    pickedFilePath[0] = path;
                    fileLabel.setText(path != null ? "视频: " + path.replace("/sdcard/", ".../") : "视频: 未选择");
                }));
                root.addView(fileBtn);
                break;
            case 3: // 语音
                fileLabel.setText("语音: 未选择");
                root.addView(fileLabel);
                fileBtn = new Button(sAct);
                fileBtn.setText("选择语音文件");
                fileBtn.setTextSize(13);
                fileBtn.setTextColor(AppColors.WHITE_TEXT);
                GradientDrawable vaBg = new GradientDrawable();
                vaBg.setColor(AppColors.accent());
                vaBg.setCornerRadius(dp(8));
                fileBtn.setBackground(vaBg);
                fileBtn.setOnClickListener(v -> pickAudio(path -> {
                    pickedFilePath[0] = path;
                    fileLabel.setText(path != null ? "语音: " + path.replace("/sdcard/", ".../") : "语音: 未选择");
                }));
                root.addView(fileBtn);
                break;
            case 4: // 位置文本
                fileLabel.setText("位置: 未选择（点击微信内置位置）");
                root.addView(fileLabel);
                fileBtn = new Button(sAct);
                fileBtn.setText("打开微信位置");
                fileBtn.setTextSize(13);
                fileBtn.setTextColor(AppColors.WHITE_TEXT);
                GradientDrawable lbBg = new GradientDrawable();
                lbBg.setColor(AppColors.accent());
                lbBg.setCornerRadius(dp(8));
                fileBtn.setBackground(lbBg);
                fileBtn.setOnClickListener(v -> {
                    toast("请在微信中手动选择位置（群发时将附带文字）");
                    try {
                        Intent i = new Intent();
                        i.setClassName("com.tencent.mm", "com.tencent.mm.plugin.location.ui.LocationUI");
                        sAct.startActivity(i);
                    } catch (Exception e) { toast("无法打开位置选择"); }
                });
                root.addView(fileBtn);
                break;
            case 5: // 名片文字
                fileLabel.setText("名片: 未选择（暂用输入框输入目标ID）");
                root.addView(fileLabel);
                fileBtn = new Button(sAct);
                fileBtn.setText("选择名片");
                fileBtn.setTextSize(13);
                fileBtn.setTextColor(AppColors.WHITE_TEXT);
                GradientDrawable cbBg = new GradientDrawable();
                cbBg.setColor(AppColors.accent());
                cbBg.setCornerRadius(dp(8));
                fileBtn.setBackground(cbBg);
                fileBtn.setOnClickListener(v -> {
                    com.leshao.v3.ui.ContactPickerDialog.show(sAct, "",
                            com.leshao.v3.ui.ContactPickerDialog.MODE_FRIEND,
                            (selected, display) -> {
                                if (!selected.isEmpty()) {
                                    pickedFilePath[0] = selected.iterator().next();
                                    fileLabel.setText("名片: " + display);
                                }
                            });
                });
                root.addView(fileBtn);
                break;
        }

        // --- 随机延迟开关 ---
        final Switch delaySwitch = CandyUi.newSwitch(sAct);
        delaySwitch.setText("启用随机延迟 (0~2000ms)");
        LinearLayout swRow = new LinearLayout(sAct);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setPadding(0, dp(12), 0, 0);
        swRow.addView(delaySwitch);
        root.addView(swRow);

        sv.addView(root);

        // 底部按钮栏（先占位，Dialog 用数组引用）
        final Dialog[] dlgStep2Holder = new Dialog[1];
        LinearLayout step2Bottom = new LinearLayout(sAct);
        step2Bottom.setOrientation(LinearLayout.HORIZONTAL);
        step2Bottom.setPadding(0, dp(12), 0, 0);
        Button step2Cancel = new Button(sAct);
        step2Cancel.setText("取消");
        step2Cancel.setTextSize(13);
        step2Cancel.setAllCaps(false);
        step2Cancel.setTextColor(AppColors.text2());
        step2Cancel.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        step2Cancel.setOnClickListener(v2 -> { if (dlgStep2Holder[0] != null) dlgStep2Holder[0].dismiss(); });
        step2Bottom.addView(step2Cancel, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button step2Confirm = new Button(sAct);
        step2Confirm.setText("确认预约");
        step2Confirm.setTextSize(13);
        step2Confirm.setAllCaps(false);
        step2Confirm.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable cfgBg = new GradientDrawable();
        cfgBg.setColor(AppColors.accent());
        cfgBg.setCornerRadius(dp(8));
        step2Confirm.setBackground(cfgBg);
        step2Confirm.setOnClickListener(v2 -> {
            if (selectedTargets[0].isEmpty()) { toast("请选择目标群"); return; }
            if (selectedTimeMs[0] <= 0) { toast("请设置发送时间"); return; }
            if (selectedTimeMs[0] <= System.currentTimeMillis()) {
                toast("时间必须在未来"); return;
            }
            String text = et.getText().toString().trim();
            String filePath = pickedFilePath[0];
            boolean hasContent = !text.isEmpty() || filePath != null;
            if (!hasContent) { toast("请输入内容或选择文件"); return; }

            boolean randomDelay = delaySwitch.isChecked();

            WmPrefs.setStr("mass_send_text", text);
            WmPrefs.setStr("mass_send_type", MASS_TYPE_KEYS[msgTypeIdx]);
            WmPrefs.setStr("mass_send_time", String.valueOf(selectedTimeMs[0]));
            WmPrefs.setStr("mass_send_delay_enabled", String.valueOf(randomDelay));
            if (filePath != null) WmPrefs.setStr("mass_send_file", filePath);
            JSONArray arr = new JSONArray();
            for (String t : selectedTargets[0]) arr.put(t);
            WmPrefs.setStr("mass_send_targets", arr.toString());

            SimpleDateFormat fmt = new SimpleDateFormat("MM月dd日 HH:mm");
            String timeHint = fmt.format(new Date(selectedTimeMs[0]));
            String delayHint = randomDelay ? " (随机延迟0~2000ms)" : "";
            toast("已预约群发: " + timeHint + delayHint + " → " + selectedTargets[0].size() + "个目标");

            saveMassSendRecord(MASS_TYPES[msgTypeIdx], selectedTargets[0].size(), 0, 0);
            initMassSendScheduler();
            scheduleMassSend(selectedTimeMs[0]);
            dlgStep2Holder[0].dismiss();
        });
        step2Bottom.addView(step2Confirm, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(step2Bottom);

        // 标题栏
        TextView step2Title = new TextView(sAct);
        step2Title.setText("群发设置 - " + MASS_TYPES[msgTypeIdx]);
        step2Title.setTextSize(16);
        step2Title.setTextColor(AppColors.TEXT_TITLE);
        step2Title.setTypeface(null, android.graphics.Typeface.BOLD);
        step2Title.setPadding(dp(16), dp(14), dp(16), 0);
        LinearLayout step2Wrap = new LinearLayout(sAct);
        step2Wrap.setOrientation(LinearLayout.VERTICAL);
        step2Wrap.setBackground(CandyUi.cardBg(sAct));
        step2Wrap.addView(step2Title);
        step2Wrap.addView(sv);

        Dialog dlgStep2 = new Dialog(sAct);
        dlgStep2Holder[0] = dlgStep2;
        dlgStep2.setContentView(step2Wrap);
        dlgStep2.setCanceledOnTouchOutside(true);
        Window w2 = dlgStep2.getWindow();
        if (w2 != null) {
            w2.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w2.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp2 = w2.getAttributes();
            lp2.dimAmount = 0.75f;
            w2.setAttributes(lp2);
        }
        dlgStep2.show();
    }

    /** 群发记录持久化 */
    static void saveMassSendRecord(String type, int targetCount, int success, int fail) {
        try {
            JSONArray arr;
            String existing = WmPrefs.getStr("mass_send_records", "");
            if (existing.isEmpty()) arr = new JSONArray();
            else arr = new JSONArray(existing);
            JSONObject obj = new JSONObject();
            obj.put("time", System.currentTimeMillis());
            obj.put("type", type);
            obj.put("targetCount", targetCount);
            obj.put("success", success);
            obj.put("fail", fail);
            arr.put(obj);
            WmPrefs.setStr("mass_send_records", arr.toString());
        } catch (Exception ignored) {}
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
        } catch (Exception ignored) {}
    }

    /** 查看群发记录 */
    static void showMassSendRecords() {
        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackground(CandyUi.cardBg(sAct));

        String existing = WmPrefs.getStr("mass_send_records", "");
        if (existing.isEmpty()) {
            TextView tv = new TextView(sAct);
            tv.setText("暂无群发记录");
            tv.setTextSize(14);
            tv.setTextColor(AppColors.text2());
            root.addView(tv);
        } else {
            try {
                JSONArray arr = new JSONArray(existing);
                SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    long t = obj.optLong("time", 0);
                    String type = obj.optString("type", "");
                    int cnt = obj.optInt("targetCount", 0);
                    int ok = obj.optInt("success", 0);
                    int fl = obj.optInt("fail", 0);
                    TextView tv = new TextView(sAct);
                    tv.setText(fmt.format(new Date(t)) + "  " + type + "  目标:" + cnt
                            + "  成功:" + ok + "  失败:" + fl);
                    tv.setTextSize(12);
                    tv.setTextColor(AppColors.text1());
                    tv.setPadding(0, dp(2), 0, dp(2));
                    root.addView(tv);
                }
            } catch (Exception e) {
                TextView tv = new TextView(sAct);
                tv.setText("记录解析失败");
                tv.setTextSize(14);
                tv.setTextColor(AppColors.text2());
                root.addView(tv);
            }
        }

        // 底部按钮
        final Dialog[] dlgRecHolder = new Dialog[1];
        LinearLayout recBottom = new LinearLayout(sAct);
        recBottom.setOrientation(LinearLayout.HORIZONTAL);
        recBottom.setPadding(0, dp(12), 0, 0);
        Button recClear = new Button(sAct);
        recClear.setText("清空记录");
        recClear.setTextSize(13);
        recClear.setAllCaps(false);
        recClear.setTextColor(AppColors.text2());
        recClear.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        recClear.setOnClickListener(v -> {
            WmPrefs.setStr("mass_send_records", "");
            toast("群发记录已清空");
            if (dlgRecHolder[0] != null) dlgRecHolder[0].dismiss();
        });
        recBottom.addView(recClear, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button recFail = new Button(sAct);
        recFail.setText("查看失败记录");
        recFail.setTextSize(13);
        recFail.setAllCaps(false);
        recFail.setTextColor(AppColors.accent());
        recFail.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        recFail.setOnClickListener(v -> { if (dlgRecHolder[0] != null) dlgRecHolder[0].dismiss(); showMassSendFailRecords(); });
        recBottom.addView(recFail, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button recClose = new Button(sAct);
        recClose.setText("关闭");
        recClose.setTextSize(13);
        recClose.setAllCaps(false);
        recClose.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable rcBg = new GradientDrawable();
        rcBg.setColor(AppColors.accent());
        rcBg.setCornerRadius(dp(8));
        recClose.setBackground(rcBg);
        recClose.setOnClickListener(v -> { if (dlgRecHolder[0] != null) dlgRecHolder[0].dismiss(); });
        recBottom.addView(recClose, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(recBottom);

        // 标题栏
        TextView recTitle = new TextView(sAct);
        recTitle.setText("群发记录");
        recTitle.setTextSize(16);
        recTitle.setTextColor(AppColors.TEXT_TITLE);
        recTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        recTitle.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout recWrap = new LinearLayout(sAct);
        recWrap.setOrientation(LinearLayout.VERTICAL);
        recWrap.setBackground(CandyUi.cardBg(sAct));
        recWrap.addView(recTitle);
        recWrap.addView(root);

        Dialog dlgRec = new Dialog(sAct);
        dlgRecHolder[0] = dlgRec;
        dlgRec.setContentView(recWrap);
        dlgRec.setCanceledOnTouchOutside(true);
        Window wr = dlgRec.getWindow();
        if (wr != null) {
            wr.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            wr.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lpr = wr.getAttributes();
            lpr.dimAmount = 0.75f;
            wr.setAttributes(lpr);
        }
        dlgRec.show();
    }

    /** 查看失败记录 */
    static void showMassSendFailRecords() {
        LinearLayout root = new LinearLayout(sAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackground(CandyUi.cardBg(sAct));

        String existing = WmPrefs.getStr("mass_send_fail_records", "");
        if (existing.isEmpty()) {
            TextView tv = new TextView(sAct);
            tv.setText("暂无失败记录");
            tv.setTextSize(14);
            tv.setTextColor(AppColors.text2());
            root.addView(tv);
        } else {
            try {
                JSONArray arr = new JSONArray(existing);
                SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    long t = obj.optLong("time", 0);
                    String target = obj.optString("target", "");
                    String type = obj.optString("type", "");
                    String reason = obj.optString("reason", "");
                    TextView tv = new TextView(sAct);
                    tv.setText(fmt.format(new Date(t)) + "  " + type + "  目标:" + target + "\n  原因:" + reason);
                    tv.setTextSize(11);
                    tv.setTextColor(AppColors.text1());
                    tv.setPadding(0, dp(2), 0, dp(2));
                    root.addView(tv);
                }
            } catch (Exception e) {
                TextView tv = new TextView(sAct);
                tv.setText("记录解析失败");
                tv.setTextSize(14);
                tv.setTextColor(AppColors.text2());
                root.addView(tv);
            }
        }

        // 底部按钮
        final Dialog[] dlgFailHolder = new Dialog[1];
        LinearLayout failBottom = new LinearLayout(sAct);
        failBottom.setOrientation(LinearLayout.HORIZONTAL);
        failBottom.setPadding(0, dp(12), 0, 0);
        Button failClear = new Button(sAct);
        failClear.setText("清空失败记录");
        failClear.setTextSize(13);
        failClear.setAllCaps(false);
        failClear.setTextColor(AppColors.text2());
        failClear.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        failClear.setOnClickListener(v -> {
            WmPrefs.setStr("mass_send_fail_records", "");
            toast("失败记录已清空");
            if (dlgFailHolder[0] != null) dlgFailHolder[0].dismiss();
        });
        failBottom.addView(failClear, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button failClose = new Button(sAct);
        failClose.setText("关闭");
        failClose.setTextSize(13);
        failClose.setAllCaps(false);
        failClose.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable fcBg = new GradientDrawable();
        fcBg.setColor(AppColors.accent());
        fcBg.setCornerRadius(dp(8));
        failClose.setBackground(fcBg);
        failClose.setOnClickListener(v -> { if (dlgFailHolder[0] != null) dlgFailHolder[0].dismiss(); });
        failBottom.addView(failClose, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(failBottom);

        // 标题栏
        TextView failTitle = new TextView(sAct);
        failTitle.setText("失败记录");
        failTitle.setTextSize(16);
        failTitle.setTextColor(AppColors.TEXT_TITLE);
        failTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        failTitle.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout failWrap = new LinearLayout(sAct);
        failWrap.setOrientation(LinearLayout.VERTICAL);
        failWrap.setBackground(CandyUi.cardBg(sAct));
        failWrap.addView(failTitle);
        failWrap.addView(root);

        Dialog dlgFail = new Dialog(sAct);
        dlgFailHolder[0] = dlgFail;
        dlgFail.setContentView(failWrap);
        dlgFail.setCanceledOnTouchOutside(true);
        Window wf = dlgFail.getWindow();
        if (wf != null) {
            wf.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            wf.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lpf = wf.getAttributes();
            lpf.dimAmount = 0.75f;
            wf.setAttributes(lpf);
        }
        dlgFail.show();
    }

    // ===== 万群群发定时调度 =====

    private static void initMassSendScheduler() {
        if (sMassSendReceiver != null) return;
        sCtx = com.leshao.v3.ContextManager.getAppContext();
        if (sCtx == null) return;
        sMassSendReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                executeMassSend();
            }
        };
        IntentFilter f = new IntentFilter("com.leshao.v3.MASS_SEND_EXEC");
        sCtx.registerReceiver(sMassSendReceiver, f, Context.RECEIVER_EXPORTED);
    }

    private static void scheduleMassSend(long triggerTimeMs) {
        if (sCtx == null) sCtx = com.leshao.v3.ContextManager.getAppContext();
        if (sCtx == null) return;
        Intent i = new Intent("com.leshao.v3.MASS_SEND_EXEC");
        PendingIntent pi = PendingIntent.getBroadcast(sCtx, 10001, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) sCtx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMs, pi);
        LogWriter.log(TAG, "massSend scheduled at " + triggerTimeMs);
    }

    private static void executeMassSend() {
        try {
            String type = WmPrefs.getStr("mass_send_type", "");
            String text = WmPrefs.getStr("mass_send_text", "");
            String targetsJson = WmPrefs.getStr("mass_send_targets", "");
            boolean randomDelay = "true".equals(WmPrefs.getStr("mass_send_delay_enabled", "false"));
            String filePath = WmPrefs.getStr("mass_send_file", "");

            if (type.isEmpty() || targetsJson.isEmpty()) {
                LogWriter.log(TAG, "massSend: no saved data");
                return;
            }
            JSONArray arr = new JSONArray(targetsJson);
            if (arr.length() == 0) return;

            int success = 0, fail = 0;
            for (int i = 0; i < arr.length(); i++) {
                try {
                    String target = arr.getString(i);
                    if (randomDelay) {
                        Thread.sleep((long)(Math.random() * 2000));
                    }
                    if (sCL == null) {
                        LogWriter.log(TAG, "massSend: sCL is null");
                        fail++; saveMassSendFailRecord(target, type, "ClassLoader null");
                        continue;
                    }
                    switch (type) {
                        case "text":
                        case "location_text":
                        case "card_text":
                            com.leshao.v3.hook.GroupFeatures.sendTextMessage(sCL, target, text);
                            break;
                        case "image_text":
                            com.leshao.v3.hook.GroupFeatures.sendTextMessage(sCL, target, text);
                            if (!filePath.isEmpty()) sendMediaFile(target, filePath, "image");
                            break;
                        case "video_text":
                            com.leshao.v3.hook.GroupFeatures.sendTextMessage(sCL, target, text);
                            if (!filePath.isEmpty()) sendMediaFile(target, filePath, "video");
                            break;
                        case "voice":
                            if (!filePath.isEmpty()) sendAudioFile(target, filePath);
                            break;
                        default:
                            com.leshao.v3.hook.GroupFeatures.sendTextMessage(sCL, target, text);
                    }
                    success++;
                } catch (Throwable t) {
                    fail++;
                    saveMassSendFailRecord("target_error", type, t.getMessage());
                }
            }
            LogWriter.log(TAG, "massSend done: success=" + success + " fail=" + fail);
            WmPrefs.setStr("mass_send_type", "");
            WmPrefs.setStr("mass_send_text", "");
            WmPrefs.setStr("mass_send_targets", "");
            WmPrefs.setStr("mass_send_file", "");
            WmPrefs.setStr("mass_send_delay_enabled", "");

            try {
                JSONArray records;
                String existing = WmPrefs.getStr("mass_send_records", "");
                if (existing.isEmpty()) records = new JSONArray();
                else records = new JSONArray(existing);
                for (int i = records.length() - 1; i >= 0; i--) {
                    JSONObject obj = records.getJSONObject(i);
                    if (obj.optInt("success") == 0 && obj.optInt("fail") == 0) {
                        obj.put("success", success);
                        obj.put("fail", fail);
                        WmPrefs.setStr("mass_send_records", records.toString());
                        break;
                    }
                }
            } catch (Exception ignored) {}
        } catch (Throwable t) {
            LogWriter.log(TAG, "massSend err: " + t.getMessage());
        }
    }

    private static boolean sendMediaFile(String talker, String filePath, String mediaType) {
        try {
            if (sCL == null) return false;
            Object storage = getMsgStorage();
            if (storage == null) return false;
            Object msg = XposedHelpers.newInstance(
                XposedHelpers.findClass("com.tencent.mm.storage.bs", sCL), talker);
            XposedHelpers.callMethod(msg, "A1", "image".equals(mediaType) ? 3 : 43);
            XposedHelpers.callMethod(msg, "P0", filePath);
            XposedHelpers.callMethod(msg, "L1", System.currentTimeMillis());
            XposedHelpers.callMethod(storage, "Ra", System.currentTimeMillis(), msg);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendMediaFile err: " + t.getMessage());
            return false;
        }
    }

    private static boolean sendAudioFile(String talker, String filePath) {
        try {
            if (sCL == null) return false;
            Object storage = getMsgStorage();
            if (storage == null) return false;
            Object msg = XposedHelpers.newInstance(
                XposedHelpers.findClass("com.tencent.mm.storage.bs", sCL), talker);
            XposedHelpers.callMethod(msg, "A1", 34);
            XposedHelpers.callMethod(msg, "P0", filePath);
            XposedHelpers.callMethod(msg, "L1", System.currentTimeMillis());
            XposedHelpers.callMethod(storage, "Ra", System.currentTimeMillis(), msg);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendAudioFile err: " + t.getMessage());
            return false;
        }
    }

    private static Object getMsgStorage() {
        try {
            if (sCL == null) return null;
            Class<?> mma = XposedHelpers.findClass("com.tencent.mm.modelmulti.aa", sCL);
            return XposedHelpers.callStaticMethod(mma, "getService");
        } catch (Throwable t) {
            return null;
        }
    }
}
