package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.leshao.v3.LogWriter;
import java.util.ArrayList;
import java.util.List;

public class MusicActivity extends Activity {

    static final int CLR_BG = 0xFFF5F7FA;
    static final int CLR_CARD = 0xFFFFFFFF;
    static final int CLR_ACCENT = 0xFF3B8EFF;
    static final int CLR_ACCENT_LIGHT = 0xFFE8F0FE;
    static final int CLR_TEXT = 0xFF1A1A2E;
    static final int CLR_TEXT2 = 0xFF6B7280;
    static final int CLR_DIV = 0xFFE5E7EB;
    static final int CLR_INPUT = 0xFFEEF0F4;
    static final int CLR_GOLD = 0xFFF59E0B;
    static final int CLR_RED = 0xFFEF4444;

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static float sDensity;
    static MusicPlayerManager sPlayer;
    static Activity sActivity;
    static MusicActivity sInstance;

    FrameLayout mContent;
    LinearLayout mBottomNav;
    LinearLayout mPlayerBar;
    ImageView mPlayerCover;
    TextView mPlayerTitle;
    ImageView mPlayerPlayBtn;
    ImageView mPlayerNextBtn;
    int mCurrentTab = 0;

    static final String[] TAB_LABELS = {"推荐", "排行", "歌单", "搜索"};
    static final String[] TAB_ICONS = {"🏠", "🏆", "🎵", "🔍"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            sDensity = getResources().getDisplayMetrics().density;
            sPlayer = MusicPlayerManager.get(this);
            sActivity = this;
            sInstance = this;

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(CLR_BG);

            mContent = new FrameLayout(this);
            mContent.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));
            root.addView(mContent);

            buildPlayerBar();
            root.addView(mPlayerBar);

            buildBottomNav();
            root.addView(mBottomNav);

            setContentView(root);

            showTab(0);

            LogWriter.log("MusicActivity", "onCreate OK");
        } catch (Throwable e) {
            LogWriter.log("MusicActivity", "onCreate CRASH: " + Log.getStackTraceString(e));
            Toast.makeText(this, "启动失败: " + e.toString(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    void buildPlayerBar() {
        mPlayerBar = new LinearLayout(this);
        mPlayerBar.setOrientation(LinearLayout.HORIZONTAL);
        mPlayerBar.setGravity(Gravity.CENTER_VERTICAL);
        mPlayerBar.setBackgroundColor(CLR_CARD);
        mPlayerBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        mPlayerBar.setVisibility(View.GONE);
        mPlayerBar.setElevation(dp(3));
        mPlayerBar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(41)));

        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(dp(4));
        coverBg.setColor(0xFFDDDDDD);

        mPlayerCover = new ImageView(this);
        int cs = dp(36);
        mPlayerCover.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        mPlayerCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mPlayerCover.setBackground(coverBg);
        mPlayerBar.addView(mPlayerCover);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(dp(7), 0, dp(6), 0);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        mPlayerTitle = new TextView(this);
        mPlayerTitle.setTextSize(10);
        mPlayerTitle.setTextColor(CLR_TEXT);
        mPlayerTitle.setSingleLine(true);
        mPlayerTitle.setTypeface(null, Typeface.BOLD);
        infoCol.addView(mPlayerTitle);
        mPlayerBar.addView(infoCol);

        mPlayerPlayBtn = new ImageView(this);
        int bs = dp(32);
        mPlayerPlayBtn.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        mPlayerPlayBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mPlayerPlayBtn.setOnClickListener(v -> {
            if (sPlayer != null) sPlayer.togglePause();
            refreshPlayerBar();
        });
        mPlayerBar.addView(mPlayerPlayBtn);

        mPlayerNextBtn = new ImageView(this);
        mPlayerNextBtn.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        mPlayerNextBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mPlayerNextBtn.setImageDrawable(emoji("\u23ED", dp(13)));
        mPlayerNextBtn.setOnClickListener(v -> {
            if (sPlayer != null) { sPlayer.next(); refreshPlayerBar(); }
        });
        mPlayerBar.addView(mPlayerNextBtn);

        mPlayerBar.setOnClickListener(v -> {
            if (sPlayer != null && sPlayer.getCurrent() != null) {
                startActivity(new Intent(this, MusicPlayerActivity.class));
            }
        });
    }

    void refreshPlayerBar() {
        MusicSearchApi.Song song = sPlayer != null ? sPlayer.getCurrent() : null;
        if (song == null) { mPlayerBar.setVisibility(View.GONE); return; }
        mPlayerBar.setVisibility(View.VISIBLE);
        mPlayerTitle.setText(song.title + " - " + song.artist);
        boolean playing = sPlayer.isPlaying();
        mPlayerPlayBtn.setImageDrawable(emoji(playing ? "\u23F8" : "\u25B6", dp(13)));
        loadCover(mPlayerCover, song.cover);
    }

    void buildBottomNav() {
        mBottomNav = new LinearLayout(this);
        mBottomNav.setOrientation(LinearLayout.HORIZONTAL);
        mBottomNav.setBackgroundColor(CLR_CARD);
        mBottomNav.setElevation(dp(6));
        mBottomNav.setPadding(dp(3), dp(3), dp(3), dp(1));

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1.0f));

            TextView icon = new TextView(this);
            icon.setText(TAB_ICONS[i]);
            icon.setTextSize(16);
            icon.setGravity(Gravity.CENTER);
            tab.addView(icon);

            TextView label = new TextView(this);
            label.setText(TAB_LABELS[i]);
            label.setTextSize(10);
            label.setGravity(Gravity.CENTER);
            label.setTypeface(null, Typeface.BOLD);
            tab.addView(label);

            tab.setTag(new View[]{icon, label});

            tab.setOnClickListener(v -> showTab(idx));
            mBottomNav.addView(tab);
        }
        updateNavHighlight();
    }

    void showTab(int idx) {
        mCurrentTab = idx;
        mContent.removeAllViews();

        View view = null;
        switch (idx) {
            case 0: {
                MusicHomeView hv = new MusicHomeView();
                view = hv.createView(this);
                hv.onViewReady();
                break;
            }
            case 1: {
                MusicRankingView rv = new MusicRankingView();
                view = rv.createView(this);
                rv.onViewReady();
                break;
            }
            case 2: {
                MusicPlaylistView pv = new MusicPlaylistView();
                view = pv.createView(this);
                pv.onViewReady();
                break;
            }
            case 3: {
                MusicSearchView sv = new MusicSearchView();
                view = sv.createView(this);
                sv.onViewReady();
                break;
            }
        }
        if (view != null) mContent.addView(view);
        updateNavHighlight();
    }

    void updateNavHighlight() {
        for (int i = 0; i < mBottomNav.getChildCount(); i++) {
            View child = mBottomNav.getChildAt(i);
            View[] views = (View[]) child.getTag();
            if (views != null) {
                boolean sel = (i == mCurrentTab);
                ((TextView) views[0]).setTextColor(sel ? CLR_ACCENT : CLR_TEXT2);
                ((TextView) views[1]).setTextColor(sel ? CLR_ACCENT : CLR_TEXT2);
            }
        }
    }

    public static void playSong(MusicSearchApi.Song song) {
        if (sPlayer == null) return;
        int idx = sPlayer.getPlaylist().indexOf(song);
        if (idx >= 0) {
            sPlayer.playFromPlaylist(idx);
        } else {
            sPlayer.getPlaylist().add(song);
            sPlayer.play(song);
        }
        if (sInstance != null) sInstance.refreshPlayerBar();
    }

    public static void playSongs(List<MusicSearchApi.Song> songs, int startIdx) {
        if (sPlayer == null) return;
        sPlayer.getPlaylist().clear();
        sPlayer.getPlaylist().addAll(songs);
        if (startIdx >= 0 && startIdx < songs.size()) {
            sPlayer.play(songs.get(startIdx));
        }
        if (sInstance != null) sInstance.refreshPlayerBar();
    }

    public static void loadCover(ImageView iv, String url) {
        if (url == null || url.isEmpty()) return;
        if (!url.startsWith("http")) {
            url = url.startsWith("//") ? "https:" + url : url;
            if (!url.startsWith("http")) return;
        }
        url = url.replace("{size}", "400");
        String finalUrl = url;
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(finalUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                java.io.InputStream is = conn.getInputStream();
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();
                if (bmp != null) MAIN.post(() -> {
                    iv.setImageBitmap(bmp);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                });
            } catch (Throwable ignored) {}
        }).start();
    }

    public static int dp(int dp) { return (int) (dp * sDensity + 0.5f); }

    public static int dp(float dp) { return (int) (dp * sDensity + 0.5f); }

    public static GradientDrawable rd(int radius, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radius));
        g.setColor(color);
        return g;
    }

    public static GradientDrawable rd(float[] radii, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadii(radii);
        g.setColor(color);
        return g;
    }

    public static GradientDrawable gradient(int[] colors) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
    }

    public static GradientDrawable gradientRounded(int[] colors, int radius) {
        GradientDrawable g = gradient(colors);
        g.setCornerRadius(dp(radius));
        return g;
    }

    public static android.graphics.drawable.Drawable emoji(String emoji, int sizePx) {
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(sizePx);
        float w = paint.measureText(emoji);
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        float h = fm.bottom - fm.top;
        if (w <= 0 || h <= 0) { w = sizePx; h = sizePx; }
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
            (int) Math.ceil(w) + 1, (int) Math.ceil(h) + 1,
            android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawText(emoji, 0, -fm.top, paint);
        return new android.graphics.drawable.BitmapDrawable(
            sActivity != null ? sActivity.getResources() : null, bmp);
    }

    public static void toast(String msg) {
        MAIN.post(() -> {
            if (sActivity != null) Toast.makeText(sActivity, msg, Toast.LENGTH_SHORT).show();
        });
    }
}
