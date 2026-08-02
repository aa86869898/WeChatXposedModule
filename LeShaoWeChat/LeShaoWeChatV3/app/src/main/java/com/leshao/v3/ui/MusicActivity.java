package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class MusicActivity extends Activity {

    static int CLR_BG = 0xFFF0F4FA;
    static int CLR_CARD = 0xFFFFFFFF;
    static int CLR_ACCENT = 0xFF3B8EFF;
    static int CLR_ACCENT_LIGHT = 0xFFE8F0FE;
    static int CLR_TEXT = 0xFF1A1A2E;
    static int CLR_TEXT2 = 0xFF6B7280;
    static int CLR_DIV = 0xFFE5E7EB;
    static int CLR_INPUT = 0xFFEEF2F7;
    static int CLR_GOLD = 0xFFF59E0B;
    static int CLR_RED = 0xFFEF4444;
    static int CLR_ACCENT_DARK = 0xFF2E6FD4;
    static boolean sIsDark = false;

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static float sDensity;
    static int sStatusBarH;
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
    MiniProgressView mPlayerProgress;
    RotateAnimation mCoverRotate;
    Runnable mMiniProgressRunner;
    int mCurrentTab = 0;
    MusicRankingView mRankingView;
    MusicHomeView mHomeView;

    static final String[] TAB_LABELS = {"推荐", "排行", "播放器", "我的"};
    static final String[] TAB_ICONS = {"\uD83C\uDFE0", "\uD83C\uDFC6", "\uD83C\uDFB5", "\uD83D\uDC64"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            MusicLog.init();
            MusicLog.i("MusicActivity", "onCreate start, v=1.4.9-music");
            sDensity = getResources().getDisplayMetrics().density;
            int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
            sStatusBarH = rid > 0 ? getResources().getDimensionPixelSize(rid) : dp(24);
            sPlayer = MusicPlayerManager.get(this);
            sActivity = this;
            sInstance = this;

            applyTheme();

            sPlayer.addCallback(new MusicPlayerManager.PlayerCallback() {
                @Override public void onPlayStateChanged(boolean playing) {
                    MAIN.post(() -> refreshPlayerBar());
                }
                @Override public void onProgressChanged(int position, int duration) {}
                @Override public void onSongChanged(MusicSearchApi.Song song, int index) {
                    MAIN.post(() -> refreshPlayerBar());
                }
            });

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
            refreshPlayerBar();

            autoPlayIfNeeded();

            Log.d("MusicActivity", "onCreate OK");
        } catch (Throwable e) {
            Log.e("MusicActivity", "onCreate CRASH: " + Log.getStackTraceString(e));
            Toast.makeText(this, "启动失败: " + e.toString(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPlayerBar();
        if (mMiniProgressRunner != null) {
            MAIN.post(mMiniProgressRunner);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMiniProgressRunner != null) {
            MAIN.removeCallbacks(mMiniProgressRunner);
        }
    }

    @Override
    public void onBackPressed() {
        if (mHomeView != null && mHomeView.hideSearchIfShown()) return;
        if (mRankingView != null && mRankingView.isDetailShown()) {
            mRankingView.showRankingView();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean wasDark = sIsDark;
        sIsDark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (wasDark != sIsDark) {
            applyThemeColors();
            if (mPlayerBar != null) mPlayerBar.setBackgroundColor(CLR_CARD);
            if (mBottomNav != null) mBottomNav.setBackgroundColor(CLR_CARD);
            if (mContent != null) {
                mContent.getRootView().setBackgroundColor(CLR_BG);
            }
            showTab(mCurrentTab);
        }
    }

    void applyTheme() {
        sIsDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        applyThemeColors();
    }

    static void applyThemeColors() {
        if (sIsDark) {
            CLR_BG = 0xFF121212;
            CLR_CARD = 0xFF1E1E2E;
            CLR_ACCENT = 0xFF6B8EFF;
            CLR_ACCENT_LIGHT = 0xFF1A2A4A;
            CLR_TEXT = 0xFFE0E0E0;
            CLR_TEXT2 = 0xFF9CA3AF;
            CLR_DIV = 0xFF2A2A3C;
            CLR_INPUT = 0xFF252535;
            CLR_GOLD = 0xFFF59E0B;
            CLR_RED = 0xFFEF4444;
            CLR_ACCENT_DARK = 0xFF5580EE;
        } else {
            CLR_BG = 0xFFF0F4FA;
            CLR_CARD = 0xFFFFFFFF;
            CLR_ACCENT = 0xFF3B8EFF;
            CLR_ACCENT_LIGHT = 0xFFE8F0FE;
            CLR_TEXT = 0xFF1A1A2E;
            CLR_TEXT2 = 0xFF6B7280;
            CLR_DIV = 0xFFE5E7EB;
            CLR_INPUT = 0xFFEEF2F7;
            CLR_GOLD = 0xFFF59E0B;
            CLR_RED = 0xFFEF4444;
            CLR_ACCENT_DARK = 0xFF2E6FD4;
        }
    }

    void autoPlayIfNeeded() {
        if (sPlayer == null || sPlayer.getCurrent() != null) return;
        try {
            KgApi.getTopListDetail("8888", 1, new KgApi.PlaylistSongsCallback() {
                public void onResult(List<KgApi.Song> songs, int total) {
                    if (sPlayer != null && songs != null && !songs.isEmpty()) {
                        MusicSearchApi.Song ms = new MusicSearchApi.Song();
                        KgApi.Song ks = songs.get(0);
                        String hash = ks.hash != null ? ks.hash : "";
                        String id = ks.id != null ? ks.id : hash;
                        ms.id = hash.isEmpty() ? id : hash;
                        ms.hash = hash;
                        ms.hash320 = ks.hash320;
                        ms.sqHash = ks.sqHash;
                        ms.title = ks.title != null ? ks.title : "未知歌曲";
                        ms.artist = ks.artist != null ? ks.artist : "未知歌手";
                        ms.cover = ks.cover != null ? ks.cover : "";
                        ms.duration = ks.duration;
                        ms.platform = 0;
                        sPlayer.play(ms);
                        refreshPlayerBar();
                    }
                }
                public void onError(String msg) {}
            });
        } catch (Throwable ignored) {}
    }

    void buildPlayerBar() {
        mPlayerBar = new LinearLayout(this);
        mPlayerBar.setOrientation(LinearLayout.VERTICAL);
        mPlayerBar.setBackgroundColor(CLR_CARD);
        mPlayerBar.setPadding(dp(8), dp(5), dp(8), dp(4));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        int cs = dp(30);
        FrameLayout coverWrap = new FrameLayout(this);
        coverWrap.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));

        GradientDrawable discBg = new GradientDrawable();
        discBg.setCornerRadius(cs / 2);
        discBg.setColor(0xFFDDDDDD);

        mPlayerCover = new ImageView(this);
        FrameLayout.LayoutParams covLp = new FrameLayout.LayoutParams(cs, cs);
        covLp.gravity = Gravity.CENTER;
        mPlayerCover.setLayoutParams(covLp);
        mPlayerCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mPlayerCover.setBackground(discBg);
        mPlayerCover.setOnClickListener(v -> openPlayer());
        coverWrap.addView(mPlayerCover);
        row.addView(coverWrap);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(dp(7), 0, dp(6), 0);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        infoCol.setOnClickListener(v -> openPlayer());
        mPlayerTitle = new TextView(this);
        mPlayerTitle.setTextSize(10);
        mPlayerTitle.setTextColor(CLR_TEXT);
        mPlayerTitle.setSingleLine(true);
        mPlayerTitle.setTypeface(null, Typeface.BOLD);
        infoCol.addView(mPlayerTitle);
        row.addView(infoCol);

        int bs = dp(28);
        mPlayerPlayBtn = new ImageView(this);
        mPlayerPlayBtn.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        mPlayerPlayBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mPlayerPlayBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        mPlayerPlayBtn.setImageDrawable(emoji("\u25B6", dp(13)));
        mPlayerPlayBtn.setOnClickListener(v -> {
            if (sPlayer != null && sPlayer.getCurrent() != null) {
                if (sPlayer.isPlaying()) sPlayer.pause(); else sPlayer.resume();
                refreshPlayerBar();
            }
        });
        row.addView(mPlayerPlayBtn);

        mPlayerNextBtn = new ImageView(this);
        mPlayerNextBtn.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        mPlayerNextBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mPlayerNextBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        mPlayerNextBtn.setImageDrawable(emoji("\u23ED", dp(13)));
        mPlayerNextBtn.setOnClickListener(v -> { if (sPlayer != null) { sPlayer.next(); refreshPlayerBar(); } });
        row.addView(mPlayerNextBtn);

        mPlayerBar.addView(row);

        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(4)));
        mPlayerBar.addView(gap);

        mPlayerProgress = new MiniProgressView(this);
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(-1, dp(14));
        mPlayerProgress.setLayoutParams(ppLp);
        mPlayerProgress.setProgress(0);
        mPlayerProgress.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN
                    || event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                float x = event.getX();
                float w = v.getWidth();
                if (w > 0 && sPlayer != null) {
                    float ratio = Math.max(0, Math.min(1, x / w));
                    int dur = sPlayer.getDuration();
                    if (dur > 0) {
                        sPlayer.seekTo((int) (dur * ratio));
                        mPlayerProgress.setProgress(ratio);
                    }
                }
                return true;
            }
            return false;
        });
        mPlayerBar.addView(mPlayerProgress);

        mCoverRotate = new RotateAnimation(0, 360,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        mCoverRotate.setDuration(8000);
        mCoverRotate.setRepeatCount(Animation.INFINITE);
        mCoverRotate.setInterpolator(new LinearInterpolator());

        mMiniProgressRunner = new Runnable() {
            @Override
            public void run() {
                if (sPlayer != null && sPlayer.isPlaying()) {
                    int dur = sPlayer.getDuration();
                    int pos = sPlayer.getPosition();
                    if (dur > 0 && mPlayerProgress != null) {
                        mPlayerProgress.setProgress((float) pos / dur);
                    }
                }
                MAIN.postDelayed(this, 500);
            }
        };
    }

    void openPlayer() {
        MusicLog.i("MusicActivity", "openPlayer called, player=" + (sPlayer != null) + ", current=" + (sPlayer != null ? sPlayer.getCurrent() : null));
        startActivity(new Intent(this, MusicPlayerActivity.class));
    }

    void refreshPlayerBar() {
        if (mPlayerBar == null) return;
        MusicSearchApi.Song song = sPlayer != null ? sPlayer.getCurrent() : null;
        if (song == null) {
            mPlayerTitle.setText("未在播放");
            mPlayerPlayBtn.setImageDrawable(emoji("\u25B6", dp(13)));
            mPlayerCover.setImageBitmap(null);
            mPlayerCover.clearAnimation();
            if (mPlayerProgress != null) mPlayerProgress.setProgress(0);
            return;
        }
        mPlayerTitle.setText((song.title != null ? song.title : "") + " - " + (song.artist != null ? song.artist : ""));
        boolean playing = sPlayer.isPlaying();
        mPlayerPlayBtn.setImageDrawable(emoji(playing ? "\u23F8" : "\u25B6", dp(13)));
        loadCircularCover(mPlayerCover, song.cover);

        if (playing) {
            if (mPlayerCover.getAnimation() == null) {
                mPlayerCover.startAnimation(mCoverRotate);
            }
            if (mMiniProgressRunner != null) {
                MAIN.removeCallbacks(mMiniProgressRunner);
                MAIN.post(mMiniProgressRunner);
            }
        } else {
            mPlayerCover.clearAnimation();
        }
    }

    void buildBottomNav() {
        mBottomNav = new LinearLayout(this);
        mBottomNav.setOrientation(LinearLayout.HORIZONTAL);
        mBottomNav.setBackgroundColor(CLR_CARD);
        mBottomNav.setPadding(dp(3), dp(3), dp(3), dp(1));

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, dp(42), 1.0f));

            TextView icon = new TextView(this);
            icon.setText(TAB_ICONS[i]);
            icon.setTextSize(15);
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
        if (idx == 2) {
            int savedTab = mCurrentTab;
            mCurrentTab = 2;
            updateNavHighlight();
            openPlayer();
            mCurrentTab = savedTab;
            return;
        }
        mCurrentTab = idx;
        mContent.removeAllViews();

        View view = null;
        switch (idx) {
            case 0: {
                MusicHomeView hv = new MusicHomeView();
                view = hv.createView(this);
                hv.onViewReady();
                mHomeView = hv;
                break;
            }
            case 1: {
                MusicRankingView rv = new MusicRankingView();
                view = rv.createView(this);
                rv.onViewReady();
                mRankingView = rv;
                break;
            }
            case 3: {
                MineView mv = new MineView();
                view = mv.createView(this);
                mv.onViewReady();
                break;
            }
        }
        if (view != null) {
            mContent.addView(view);
            if (view instanceof ScrollView) ((ScrollView) view).scrollTo(0, 0);
        }
        updateNavHighlight();
    }

    void updateNavHighlight() {
        for (int i = 0; i < mBottomNav.getChildCount(); i++) {
            View child = mBottomNav.getChildAt(i);
            View[] views = (View[]) child.getTag();
            if (views != null) {
                ((TextView) views[0]).setTextColor(i == mCurrentTab ? CLR_ACCENT : CLR_TEXT2);
                ((TextView) views[1]).setTextColor(i == mCurrentTab ? CLR_ACCENT : CLR_TEXT2);
            }
        }
    }

    public static void playSong(MusicSearchApi.Song song) {
        try {
            if (sPlayer == null || song == null) { toast("播放器未初始化"); return; }
            MusicLog.i("MusicActivity", "playSong: " + (song.title != null ? song.title : "") + " hash=" + song.hash);
            int idx = sPlayer.getPlaylist().indexOf(song);
            if (idx >= 0) sPlayer.playFromPlaylist(idx);
            else { sPlayer.getPlaylist().add(song); sPlayer.play(song); }
            if (sInstance != null) sInstance.refreshPlayerBar();
            toast("正在播放: " + (song.title != null ? song.title : "") + " - " + (song.artist != null ? song.artist : ""));
        } catch (Throwable e) {
            MusicLog.e("MusicActivity", "playSong crash: " + e.getMessage());
            toast("播放失败");
        }
    }

    public static void playSongs(List<MusicSearchApi.Song> songs, int startIdx) {
        if (sPlayer == null || songs.isEmpty()) return;
        sPlayer.getPlaylist().clear();
        sPlayer.getPlaylist().addAll(songs);
        if (startIdx >= 0 && startIdx < songs.size()) sPlayer.play(songs.get(startIdx));
        if (sInstance != null) sInstance.refreshPlayerBar();
    }

    public static void loadCover(ImageView iv, String url) {
        if (url == null || url.isEmpty()) return;
        if (!url.startsWith("http")) {
            if (url.startsWith("//")) url = "https:" + url;
            else return;
        }
        url = url.replace("{size}", "400");
        final String finalUrl = url;
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(finalUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Android800-AndroidPhone-12029-56-0-starlive-ctnet(13)");
                conn.setRequestProperty("Referer", "https://m.kugou.com");
                conn.setRequestProperty("KG-THash", "3e5ec6b");
                conn.setRequestProperty("KG-RC", "1");
                conn.setRequestProperty("KG-RF", "00869891");
                conn.setRequestProperty("Accept", "image/*, */*");
                int code = conn.getResponseCode();
                if (code != 200) { conn.disconnect(); return; }
                java.io.InputStream is = conn.getInputStream();
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                is.close(); conn.disconnect();
                if (bmp != null) MAIN.post(() -> iv.setImageBitmap(bmp));
            } catch (Throwable ignored) {}
        }).start();
    }

    public static void loadCircularCover(ImageView iv, String url) {
        if (url == null || url.isEmpty()) return;
        if (!url.startsWith("http")) {
            if (url.startsWith("//")) url = "https:" + url;
            else return;
        }
        url = url.replace("{size}", "400");
        final String finalUrl = url;
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(finalUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Android800-AndroidPhone-12029-56-0-starlive-ctnet(13)");
                conn.setRequestProperty("Referer", "https://m.kugou.com");
                conn.setRequestProperty("KG-THash", "3e5ec6b");
                conn.setRequestProperty("KG-RC", "1");
                conn.setRequestProperty("KG-RF", "00869891");
                conn.setRequestProperty("Accept", "image/*, */*");
                int code = conn.getResponseCode();
                if (code != 200) { conn.disconnect(); return; }
                java.io.InputStream is = conn.getInputStream();
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                is.close(); conn.disconnect();
                if (bmp != null) {
                    android.graphics.Bitmap circular = makeCircularBitmap(bmp);
                    MAIN.post(() -> iv.setImageBitmap(circular));
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    private static android.graphics.Bitmap makeCircularBitmap(android.graphics.Bitmap source) {
        int srcW = source.getWidth(), srcH = source.getHeight();
        int size = Math.min(srcW, srcH);
        android.graphics.Bitmap output = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(output);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        float r = size / 2f;
        canvas.drawCircle(r, r, r, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SRC_IN));
        float dx = (size - srcW) / 2f;
        float dy = (size - srcH) / 2f;
        canvas.drawBitmap(source, dx, dy, paint);
        return output;
    }

    public static int dp(int dp) { return (int) (dp * sDensity + 0.5f); }
    public static int dp(float dp) { return (int) (dp * sDensity + 0.5f); }

    public static GradientDrawable rd(int radius, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radius)); g.setColor(color); return g;
    }
    public static GradientDrawable gradientRounded(int[] colors, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        g.setCornerRadius(dp(radius)); return g;
    }

    public static android.graphics.drawable.Drawable emoji(String emoji, int sizePx) {
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(sizePx);
        float w = paint.measureText(emoji);
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        float h = fm.bottom - fm.top;
        if (w <= 0 || h <= 0) { w = sizePx; h = sizePx; }
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
            (int) Math.ceil(w) + 1, (int) Math.ceil(h) + 1, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawText(emoji, 0, -fm.top, paint);
        return new android.graphics.drawable.BitmapDrawable(
            sActivity != null ? sActivity.getResources() : null, bmp);
    }

    public static void toast(String msg) {
        MAIN.post(() -> { if (sActivity != null) Toast.makeText(sActivity, msg, Toast.LENGTH_SHORT).show(); });
    }

    static class MiniProgressView extends View {
        private float mProgress;
        private Paint mLinePaint, mThumbPaint;
        private static final int[] NEON_COLORS = {0xFFFF6B9D, 0xFFC44DFF, 0xFF6BC5FF, 0xFF39E6A5, 0xFFFFE259};

        MiniProgressView(Context ctx) {
            super(ctx);
            mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLinePaint.setStyle(Paint.Style.STROKE);
            mLinePaint.setStrokeCap(Paint.Cap.ROUND);

            mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mThumbPaint.setStyle(Paint.Style.FILL);
        }

        void setProgress(float p) { mProgress = p; postInvalidateOnAnimation(); }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int cy = h / 2;
            int pad = dp(1);
            int left = pad;
            int right = w - pad;
            int trackH = dp(2);

            float startX = left;
            float endX = right;

            mLinePaint.setStrokeWidth(trackH);
            mLinePaint.setColor(CLR_DIV);
            canvas.drawLine(startX, cy, endX, cy, mLinePaint);

            if (mProgress > 0) {
                float progressX = left + (right - left) * mProgress;
                mLinePaint.setShader(new LinearGradient(startX, 0, progressX, 0,
                    NEON_COLORS, null, Shader.TileMode.MIRROR));
                mLinePaint.setStrokeWidth(trackH);
                canvas.drawLine(startX, cy, progressX, cy, mLinePaint);
                mLinePaint.setShader(null);

                drawMiniHeart(canvas, progressX, cy, dp(3));
            }
        }

        private void drawMiniHeart(Canvas canvas, float cx, float cy, float size) {
            int saved = canvas.save();
            canvas.translate(cx, cy);
            Path heart = new Path();
            float s = size;
            heart.moveTo(0, s * 0.4f);
            heart.cubicTo(-s, -s * 0.4f, -s * 0.5f, -s, 0, -s * 0.3f);
            heart.cubicTo(s * 0.5f, -s, s, -s * 0.4f, 0, s * 0.4f);
            heart.close();

            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setShader(new LinearGradient(-s, 0, s, 0, NEON_COLORS, null, Shader.TileMode.MIRROR));
            sp.setStyle(Paint.Style.FILL);
            canvas.drawPath(heart, sp);
            canvas.restore();
        }
    }
}
