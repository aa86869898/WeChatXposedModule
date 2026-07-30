package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MusicPlayerView {

    private static float sD;
    private static Context sCtx;
    private static Activity sAct;

    private static ImageView sCover;
    private static TextView sTitle, sArtist, sTimeCur, sTimeTotal, sLyricText;
    private static SeekBar sSeekBar;
    private static ImageView sPlayBtn, sFavBtn;
    private static Handler sH = new Handler(Looper.getMainLooper());
    private static ObjectAnimator sRotationAnim;
    private static boolean sSeeking = false;
    private static AlertDialog sFullDialog;
    private static MusicSearchApi.Song sSong;
    private static int sQuality = 2; // 0=standard, 1=HQ, 2=lossless(exhigh)
    private static TextView sQualityLabel;

    public static View create(Context ctx, Activity parentAct, MusicSearchApi.Song song, AlertDialog dialog) {
        sCtx = ctx;
        sAct = parentAct;
        sD = ctx.getResources().getDisplayMetrics().density;
        sSong = song;
        sFullDialog = dialog;

        MusicPlayerManager pm = MusicPlayerManager.get(ctx);
        pm.addCallback(new MusicPlayerManager.PlayerCallback() {
            @Override
            public void onPlayStateChanged(boolean playing) {
                sH.post(() -> updatePlayIcon(playing));
            }
            @Override
            public void onProgressChanged(int position, int duration) {
                sH.post(() -> updateSeekBar(position, duration));
            }
            @Override
            public void onSongChanged(MusicSearchApi.Song song, int index) {
                sH.post(() -> updateSongInfo(song));
            }
        });

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());

        root.addView(buildHeader(ctx));

        ScrollView sv = new ScrollView(ctx);
        sv.setFillViewport(true);
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(24), dp(16), dp(24), dp(16));

        body.addView(buildArtwork(ctx, song));
        body.addView(buildSongInfo(ctx, song));
        body.addView(buildSeekBar(ctx));
        body.addView(buildControls(ctx));
        body.addView(buildActions(ctx));

        sv.addView(body);
        root.addView(sv);

        return root;
    }

    private static View buildHeader(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(AppColors.card());
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));

        TextView back = new TextView(ctx);
        back.setText("\u2039");
        back.setTextSize(28);
        back.setTextColor(AppColors.accent());
        back.setPadding(dp(12), dp(4), dp(4), dp(4));
        back.setOnClickListener(v -> {
            if (sFullDialog != null && sFullDialog.isShowing()) {
                sFullDialog.dismiss();
            }
        });
        bar.addView(back);

        TextView label = new TextView(ctx);
        label.setText("正在播放");
        label.setTextSize(16);
        label.setTextColor(AppColors.text1());
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(dp(4), 0, 0, 0);
        bar.addView(label);

        return bar;
    }

    private static View buildArtwork(Context ctx, MusicSearchApi.Song song) {
        FrameLayout wrapper = new FrameLayout(ctx);
        int outerSize = dp(280);
        int innerSize = dp(200);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        wrapper.setPadding(0, dp(24), 0, dp(16));

        // Vinyl disc background
        GradientDrawable discBg = new GradientDrawable();
        discBg.setShape(GradientDrawable.OVAL);
        discBg.setColor(0xFF2A2A2A);
        discBg.setStroke(dp(3), 0xFF444444);
        discBg.setSize(outerSize, outerSize);

        FrameLayout disc = new FrameLayout(ctx);
        FrameLayout.LayoutParams discLp = new FrameLayout.LayoutParams(outerSize, outerSize);
        discLp.gravity = Gravity.CENTER;
        disc.setLayoutParams(discLp);
        disc.setBackground(discBg);

        // Vinyl grooves (concentric circles)
        for (int ring = 0; ring < 3; ring++) {
            int grooveSize = outerSize - dp(20 + ring * 20);
            GradientDrawable groove = new GradientDrawable();
            groove.setShape(GradientDrawable.OVAL);
            groove.setStroke(dp(1), 0x33CCCCCC);
            groove.setSize(grooveSize, grooveSize);
            View grooveView = new View(ctx);
            FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(grooveSize, grooveSize);
            glp.gravity = Gravity.CENTER;
            grooveView.setLayoutParams(glp);
            grooveView.setBackground(groove);
            disc.addView(grooveView);
        }

        // Cover image (circular)
        sCover = new ImageView(ctx);
        FrameLayout.LayoutParams coverLp = new FrameLayout.LayoutParams(innerSize, innerSize);
        coverLp.gravity = Gravity.CENTER;
        sCover.setLayoutParams(coverLp);
        sCover.setScaleType(ImageView.ScaleType.CENTER_CROP);

        // Clip to circle
        sCover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        sCover.setClipToOutline(true);
        sCover.setBackgroundColor(0xFFFFFFFF);

        if (song.cover != null && !song.cover.isEmpty()) {
            loadCoverImage(ctx, song.cover);
        } else {
            sCover.setImageDrawable(emojiDrawable(ctx, "\uD83C\uDFB5", dp(60)));
        }

        disc.addView(sCover);

        sRotationAnim = ObjectAnimator.ofFloat(sCover, "rotation", 0f, 360f);
        sRotationAnim.setDuration(20000);
        sRotationAnim.setRepeatCount(ValueAnimator.INFINITE);
        sRotationAnim.setRepeatMode(ValueAnimator.RESTART);
        sRotationAnim.setInterpolator(new LinearInterpolator());

        wrapper.addView(disc);

        return wrapper;
    }

    private static View buildSongInfo(Context ctx, MusicSearchApi.Song song) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(0, 0, 0, dp(16));

        sTitle = new TextView(ctx);
        sTitle.setText(song.title);
        sTitle.setTextSize(18);
        sTitle.setTextColor(AppColors.text1());
        sTitle.setTypeface(null, Typeface.BOLD);
        sTitle.setGravity(Gravity.CENTER);
        sTitle.setSingleLine(true);
        col.addView(sTitle);

        sArtist = new TextView(ctx);
        sArtist.setText(song.artist);
        sArtist.setTextSize(14);
        sArtist.setTextColor(AppColors.text2());
        sArtist.setGravity(Gravity.CENTER);
        sArtist.setPadding(0, dp(4), 0, 0);
        col.addView(sArtist);

        return col;
    }

    private static View buildSeekBar(Context ctx) {
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        sSeekBar = new SeekBar(ctx);
        sSeekBar.setPadding(dp(4), 0, dp(4), 0);
        sSeekBar.setMax(1000);
        sSeekBar.setProgress(0);
        sSeekBar.setThumb(null);

        sSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
                    int dur = pm.getDuration();
                    if (dur > 0) {
                        int pos = (int) ((long) progress * dur / 1000);
                        sTimeCur.setText(MusicPageView.formatTime(pos));
                    }
                }
            }
            public void onStartTrackingTouch(SeekBar sb) { sSeeking = true; }
            public void onStopTrackingTouch(SeekBar sb) {
                MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
                int dur = pm.getDuration();
                if (dur > 0) {
                    int pos = (int) ((long) sb.getProgress() * dur / 1000);
                    pm.seekTo(pos);
                }
                sSeeking = false;
            }
        });

        wrapper.addView(sSeekBar);

        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setPadding(dp(8), dp(2), dp(8), 0);

        sTimeCur = new TextView(ctx);
        sTimeCur.setText("0:00");
        sTimeCur.setTextSize(11);
        sTimeCur.setTextColor(AppColors.text2());
        timeRow.addView(sTimeCur);

        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1.0f));
        timeRow.addView(spacer);

        sTimeTotal = new TextView(ctx);
        sTimeTotal.setText("0:00");
        sTimeTotal.setTextSize(11);
        sTimeTotal.setTextColor(AppColors.text2());
        timeRow.addView(sTimeTotal);

        wrapper.addView(timeRow);
        return wrapper;
    }

    private static View buildControls(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(8), 0, dp(4));

        int btnSize = dp(48);
        int bigSize = dp(60);

        ImageView prevBtn = makeIconBtn(ctx, "\u23EE", btnSize);
        prevBtn.setOnClickListener(v -> {
            MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
            pm.prev();
            updateSongInfo(pm.getCurrent());
        });
        row.addView(prevBtn);

        sPlayBtn = makeIconBtn(ctx, "\u25B6", bigSize);
        sPlayBtn.setOnClickListener(v -> {
            MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
            pm.togglePause();
            updatePlayIcon(pm.isPlaying());
        });
        sPlayBtn.setPadding(dp(16), 0, dp(16), 0);
        row.addView(sPlayBtn);

        ImageView nextBtn = makeIconBtn(ctx, "\u23ED", btnSize);
        nextBtn.setOnClickListener(v -> {
            MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
            pm.next();
            updateSongInfo(pm.getCurrent());
        });
        row.addView(nextBtn);

        return row;
    }

    private static View buildActions(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(4), 0, 0);

        row.addView(actionBtn(ctx, "播放列表", "\uD83C\uDFB6", () -> showPlaylistDialog()));
        row.addView(actionBtn(ctx, "播放历史", "\uD83D\uDDD2", () -> showHistoryDialog()));
        row.addView(actionBtn(ctx, "下载", "\u2B07", () -> showDownloadDialog()));
        row.addView(actionBtn(ctx, "音质选择", "\uD83C\uDFB5", () -> cycleQuality()));

        return row;
    }

    private static void cycleQuality() {
        sQuality = (sQuality + 1) % 3;
        Toast.makeText(sCtx, "音质: " + qualityName(sQuality), Toast.LENGTH_SHORT).show();
        MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
        MusicSearchApi.Song current = pm.getCurrent();
        if (current != null) {
            boolean wasPlaying = pm.isPlaying();
            if (wasPlaying) pm.togglePause();
            refetchAndPlay(current, sQuality);
        }
    }

    private static View actionBtn(Context ctx, String label, String icon, Runnable action) {
        LinearLayout btn = new LinearLayout(ctx);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        btn.setLayoutParams(lp);

        TextView iconTv = new TextView(ctx);
        iconTv.setText(icon);
        iconTv.setTextSize(20);
        iconTv.setGravity(Gravity.CENTER);
        btn.addView(iconTv);

        TextView labelTv = new TextView(ctx);
        labelTv.setText(label);
        labelTv.setTextSize(11);
        labelTv.setTextColor(AppColors.text2());
        labelTv.setGravity(Gravity.CENTER);
        labelTv.setPadding(0, dp(2), 0, 0);
        btn.addView(labelTv);

        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    // ===== Quality =====

    private static String qualityName(int q) {
        switch (q) {
            case 0: return "标准 128K";
            case 1: return "HQ 320K";
            case 2:
            default: return "无损 FLAC";
        }
    }

    private static String kgLevel(int q) {
        switch (q) {
            case 0: return "standard";
            case 1: return "high";
            case 2:
            default: return "exhigh";
        }
    }

    private static String kwFormat(int q) {
        switch (q) {
            case 0: return "mp3";
            case 1: return "aac";
            case 2:
            default: return "flac";
        }
    }

    private static void refetchAndPlay(MusicSearchApi.Song song, int quality) {
        MusicSearchApi.PlayUrlCallback cb = new MusicSearchApi.PlayUrlCallback() {
            @Override
            public void onUrl(String url) {
                MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
                pm.playUrl(url);
                sH.post(() -> updatePlayIcon(true));
            }
            @Override
            public void onError(String msg) {
                sH.post(() -> Toast.makeText(sCtx, msg, Toast.LENGTH_SHORT).show());
            }
        };
        if (song.platform == 0) {
            MusicSearchApi.getKugouPlayUrl(song.hash, kgLevel(quality), cb);
        } else {
            MusicSearchApi.getKuwoPlayUrl(song.hash, kwFormat(quality), cb);
        }
    }

    // ===== Dialogs =====

    private static void showPlaylistDialog() {
        MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
        List<MusicSearchApi.Song> list = pm.getPlaylist();
        showSongListDialog("播放列表", list, index -> pm.playFromPlaylist(index));
    }

    private static void showHistoryDialog() {
        MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
        List<MusicSearchApi.Song> list = pm.getHistory();
        showSongListDialog("播放历史", list, index -> {
            MusicSearchApi.Song s = list.get(index);
            pm.play(s);
            updateSongInfo(s);
        });
    }

    private static void showLyricDialog() {
        MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
        MusicSearchApi.Song current = pm.getCurrent();
        if (current == null) return;

        MusicSearchApi.LyricCallback cb = new MusicSearchApi.LyricCallback() {
            @Override
            public void onLyric(String lrc) {
                sH.post(() -> {
                    AlertDialog dlg = new AlertDialog.Builder(sCtx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
                        .setCancelable(true)
                        .create();
                    ScrollView sv = new ScrollView(sCtx);
                    sv.setPadding(dp(20), dp(40), dp(20), dp(40));

                    TextView tv = new TextView(sCtx);
                    tv.setText(lrc.isEmpty() ? "暂无歌词" : lrc);
                    tv.setTextSize(14);
                    tv.setTextColor(AppColors.text1());
                    tv.setLineSpacing(dp(4), 1.0f);
                    sv.addView(tv);

                    dlg.setView(sv);
                    Window w = dlg.getWindow();
                    if (w != null) {
                        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                        w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
                    }
                    dlg.show();
                });
            }
            @Override
            public void onError(String msg) {}
        };

        if (current.platform == 0) {
            MusicSearchApi.getKugouLyric(current.hash, cb);
        } else {
            MusicSearchApi.getKuwoLyric(current.hash, cb);
        }
    }

    private static void showDownloadDialog() {
        MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
        MusicSearchApi.Song current = pm.getCurrent();
        if (current == null) return;

        String[] labels = {"标准音质 128K", "HQ高音质 320K", "无损音质 FLAC"};
        final int[] levels = {0, 1, 2};

        LinearLayout root = new LinearLayout(sCtx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(20));
        root.setBackgroundColor(AppColors.bg());

        TextView hdr = new TextView(sCtx);
        hdr.setText("选择下载音质");
        hdr.setTextSize(16);
        hdr.setTextColor(AppColors.text1());
        hdr.setTypeface(null, Typeface.BOLD);
        hdr.setGravity(Gravity.CENTER);
        hdr.setPadding(0, 0, 0, dp(16));
        root.addView(hdr);

        AlertDialog dlg = new AlertDialog.Builder(sCtx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setCancelable(true)
            .create();

        for (int i = 0; i < labels.length; i++) {
            final int q = levels[i];
            final String label = labels[i];
            TextView tv = new TextView(sCtx);
            tv.setText(label);
            tv.setTextSize(14);
            tv.setTextColor(q == sQuality ? AppColors.accent() : AppColors.text1());
            tv.setPadding(dp(12), dp(12), dp(12), dp(12));
            tv.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(q == sQuality ? 0x1A007AFF : AppColors.inputBg());
            tv.setBackground(bg);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.setMargins(0, 0, 0, dp(8));
            tv.setLayoutParams(tlp);
            tv.setOnClickListener(v -> {
                dlg.dismiss();
                sH.post(() -> {
                    Toast.makeText(sCtx, "开始下载: " + label, Toast.LENGTH_SHORT).show();
                    downloadWithQuality(current, q);
                });
            });
            root.addView(tv);
        }

        dlg.setView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout((int)(280 * sD), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
        }
        dlg.show();
    }

    private static void downloadWithQuality(MusicSearchApi.Song song, int quality) {
        MusicSearchApi.PlayUrlCallback cb = new MusicSearchApi.PlayUrlCallback() {
            @Override
            public void onUrl(String url) {
                sH.post(() -> {
                    MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
                    pm.download(song, url);
                    Toast.makeText(sCtx, "已加入下载队列", Toast.LENGTH_SHORT).show();
                });
            }
            @Override
            public void onError(String msg) {
                sH.post(() -> Toast.makeText(sCtx, "下载失败: " + msg, Toast.LENGTH_SHORT).show());
            }
        };
        if (song.platform == 0) {
            MusicSearchApi.getKugouPlayUrl(song.hash, kgLevel(quality), cb);
        } else {
            MusicSearchApi.getKuwoPlayUrl(song.hash, kwFormat(quality), cb);
        }
    }

    private static void showSongListDialog(String title, List<MusicSearchApi.Song> list, SongClickListener clickListener) {
        MusicPlayerManager pm = MusicPlayerManager.get(sCtx);
        LinearLayout root = new LinearLayout(sCtx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(AppColors.bg());

        AlertDialog dlg = new AlertDialog.Builder(sCtx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setCancelable(true)
            .create();

        MusicSearchApi.Song current = pm.getCurrent();
        String curId = current != null ? current.id : null;

        for (int i = 0; i < list.size(); i++) {
            MusicSearchApi.Song s = list.get(i);
            TextView tv = new TextView(sCtx);
            tv.setText((i + 1) + ". " + s.title + " - " + s.artist);
            tv.setTextSize(14);
            tv.setTextColor(s.id != null && s.id.equals(curId) ? AppColors.accent() : AppColors.text1());
            tv.setPadding(dp(12), dp(10), dp(12), dp(10));
            tv.setSingleLine(true);
            int idx = i;
            tv.setOnClickListener(v -> {
                clickListener.onClick(idx);
                dlg.dismiss();
                updateSongInfo(pm.getCurrent());
            });
            root.addView(tv);
        }

        if (list.isEmpty()) {
            TextView empty = new TextView(sCtx);
            empty.setText("暂无内容");
            empty.setTextSize(14);
            empty.setTextColor(AppColors.text2());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(40));
            root.addView(empty);
        }

        ScrollView sv = new ScrollView(sCtx);
        sv.setFillViewport(true);
        sv.addView(root);

        dlg.setView(sv);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dlg.show();
    }

    interface SongClickListener {
        void onClick(int index);
    }

    // ===== Update Helpers =====

    static void updateSongInfo(MusicSearchApi.Song song) {
        if (song == null || sTitle == null) return;
        sTitle.setText(song.title);
        sArtist.setText(song.artist);
        sSong = song;

        if (song.cover != null && !song.cover.isEmpty() && sCover != null) {
            loadCoverImage(sCtx, song.cover);
        }
        sTimeCur.setText("0:00");
        sTimeTotal.setText("0:00");
        if (sSeekBar != null) sSeekBar.setProgress(0);
        startRotation();
    }

    static void updatePlayIcon(boolean playing) {
        if (sPlayBtn != null) {
            sPlayBtn.setImageDrawable(emojiDrawable(sCtx, playing ? "\u23F8" : "\u25B6", dp(26)));
        }
        if (playing) {
            startRotation();
        } else {
            pauseRotation();
        }
    }

    static void startRotation() {
        if (sRotationAnim != null && !sRotationAnim.isStarted()) {
            sRotationAnim.start();
        } else if (sRotationAnim != null && sRotationAnim.isPaused()) {
            sRotationAnim.resume();
        }
    }

    static void pauseRotation() {
        if (sRotationAnim != null && sRotationAnim.isStarted() && !sRotationAnim.isPaused()) {
            sRotationAnim.pause();
        }
    }

    static void updateSeekBar(int position, int duration) {
        if (sSeeking || sSeekBar == null) return;
        if (duration > 0 && sSeekBar.getMax() > 0) {
            sSeekBar.setProgress((int) ((long) position * sSeekBar.getMax() / duration));
        }
        sTimeCur.setText(MusicPageView.formatTime(position));
        sTimeTotal.setText(MusicPageView.formatTime(duration));
    }

    // ===== Cover Loading =====

    private static void loadCoverImage(Context ctx, String url) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) return;
        if (sCover == null) return;
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(url.replace("{size}", "400"));
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                java.io.InputStream is = conn.getInputStream();
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();
                if (bmp != null && sCover != null) {
                    sH.post(() -> sCover.setImageBitmap(bmp));
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    // ===== Utilities =====

    private static int dp(int dp) {
        return (int) (dp * sD + 0.5f);
    }

    private static ImageView makeIconBtn(Context ctx, String emoji, int size) {
        ImageView iv = new ImageView(ctx);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setImageDrawable(emojiDrawable(ctx, emoji, (int) (size * 0.55f)));
        return iv;
    }

    private static Drawable emojiDrawable(Context ctx, String emoji, int sizePx) {
        TextView tv = new TextView(ctx);
        tv.setText(emoji);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
        tv.setGravity(Gravity.CENTER);
        tv.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        tv.layout(0, 0, tv.getMeasuredWidth(), tv.getMeasuredHeight());
        tv.setDrawingCacheEnabled(true);
        tv.buildDrawingCache();
        android.graphics.Bitmap bmp = tv.getDrawingCache();
        if (bmp != null) {
            return new BitmapDrawable(ctx.getResources(), android.graphics.Bitmap.createBitmap(bmp));
        }
        return null;
    }
}
