package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MusicPageView {

    private static int sPlatform = 0; // 0=KuGou, 1=KuWo
    private static int sKgPage = 1, sKwPage = 1;
    private static String sKgQuery = "", sKwQuery = "";
    private static boolean sKgHasPrev, sKgHasNext, sKwHasPrev, sKwHasNext;
    private static List<MusicSearchApi.Song> sKgResults = new ArrayList<>();
    private static List<MusicSearchApi.Song> sKwResults = new ArrayList<>();
    private static LinearLayout sResultsContainer;
    private static TextView sLoadMoreBtn;
    private static LinearLayout sTabBar;
    private static EditText sSearchBox;
    private static float sDensity;
    private static Context sCtx;
    private static Activity sActivity;
    private static MusicPlayerManager sPlayer;

    private static FrameLayout sMiniPlayerBar;
    private static ImageView sMiniCover;
    private static TextView sMiniTitle;
    private static ImageView sMiniPlayBtn;
    private static TextView sMiniTime;
    private static Handler sH = new Handler(Looper.getMainLooper());
    private static String sLastCoverUrl;
    private static View sTabKuGou, sTabKuWo;
    private static ScrollView sScrollView;

    public static View create(Context ctx, Activity parentAct) {
        sCtx = ctx;
        sActivity = parentAct;
        sDensity = ctx.getResources().getDisplayMetrics().density;
        sPlayer = MusicPlayerManager.get(ctx);

        sPlayer.addCallback(new MusicPlayerManager.PlayerCallback() {
            @Override
            public void onPlayStateChanged(boolean playing) {
                sH.post(() -> updatePlayButtonState(playing));
            }
            @Override
            public void onProgressChanged(int position, int duration) {
                sH.post(() -> {
                    if (sMiniTime != null && duration > 0) {
                        sMiniTime.setText(formatTime(position) + " / " + formatTime(duration));
                    }
                });
            }
            @Override
            public void onSongChanged(MusicSearchApi.Song song, int index) {
                sH.post(() -> updateMiniPlayer());
            }
        });

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppColors.bg());
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

        root.addView(buildTabBar(ctx));

        root.addView(buildSearchBar(ctx));

        sScrollView = new ScrollView(ctx);
        sScrollView.setFillViewport(true);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        sScrollView.setLayoutParams(svLp);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(8), dp(12), dp(8));

        sResultsContainer = new LinearLayout(ctx);
        sResultsContainer.setOrientation(LinearLayout.VERTICAL);
        body.addView(sResultsContainer);

        sLoadMoreBtn = new TextView(ctx);
        sLoadMoreBtn.setText("加载更多");
        sLoadMoreBtn.setTextSize(13);
        sLoadMoreBtn.setTextColor(AppColors.accent());
        sLoadMoreBtn.setTypeface(null, Typeface.BOLD);
        sLoadMoreBtn.setGravity(Gravity.CENTER);
        sLoadMoreBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable lmbg = new GradientDrawable();
        lmbg.setCornerRadius(dp(6));
        lmbg.setColor(AppColors.inputBg());
        sLoadMoreBtn.setBackground(lmbg);
        sLoadMoreBtn.setVisibility(View.GONE);
        sLoadMoreBtn.setOnClickListener(v -> {
            setCurrentPage(getCurrentPage() + 1);
            doSearch();
        });
        body.addView(sLoadMoreBtn);

        sScrollView.addView(body);
        root.addView(sScrollView);

        sScrollView.setOnScrollChangeListener((v, sx, sy, ox, oy) -> {
            if (sLoadMoreBtn.getVisibility() != View.VISIBLE) return;
            View child = sScrollView.getChildAt(0);
            if (child == null) return;
            int diff = (child.getBottom() - (sScrollView.getHeight() + sy));
            if (diff <= dp(20) && sLoadMoreBtn.getVisibility() == View.VISIBLE) {
                sLoadMoreBtn.performClick();
            }
        });

        buildMiniPlayer(ctx);
        root.addView(sMiniPlayerBar);

        return root;
    }

    // ===== Tab Bar =====

    private static View buildTabBar(Context ctx) {
        sTabBar = new LinearLayout(ctx);
        sTabBar.setOrientation(LinearLayout.HORIZONTAL);
        sTabBar.setGravity(Gravity.CENTER);
        sTabBar.setBackgroundColor(AppColors.card());
        sTabBar.setPadding(dp(4), dp(8), dp(4), dp(8));

        sTabKuGou = tabPill(ctx, "酷狗音乐", 0xFF00B4FF, 0);
        sTabKuWo = tabPill(ctx, "酷我音乐", 0xFFFF6B00, 1);
        sTabBar.addView(sTabKuGou);
        sTabBar.addView(sTabKuWo);
        return sTabBar;
    }

    private static View tabPill(Context ctx, String label, int accent, final int platform) {
        boolean selected = (sPlatform == platform);
        int w = dp(120);

        FrameLayout wrapper = new FrameLayout(ctx);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(w, dp(38));
        wlp.setMargins(dp(6), 0, dp(6), 0);
        wrapper.setLayoutParams(wlp);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(20));
        pillBg.setColor(selected ? accent : AppColors.inputBg());

        FrameLayout pill = new FrameLayout(ctx);
        pill.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        pill.setBackground(pillBg);
        pill.setTag("pill");

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(selected ? 0xFFFFFFFF : AppColors.text1());
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTag(platform);
        tv.setTag("label");
        tv.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        pill.addView(tv);
        wrapper.addView(pill);

        wrapper.setOnClickListener(v -> {
            sPlatform = platform;
            refreshTabPills(ctx);
            loadCurrentTab();
        });
        return wrapper;
    }

    private static void refreshTabPills(Context ctx) {
        if (sTabKuGou == null || sTabKuWo == null) return;
        updatePillColor(sTabKuGou, sPlatform == 0, 0xFF00B4FF);
        updatePillColor(sTabKuWo, sPlatform == 1, 0xFFFF6B00);
        updateSearchHint();
    }

    private static void updatePillColor(View wrapper, boolean selected, int accent) {
        if (!(wrapper instanceof FrameLayout)) return;
        View pill = ((FrameLayout) wrapper).getChildAt(0);
        if (!(pill instanceof FrameLayout)) return;
        View label = ((FrameLayout) pill).getChildAt(0);
        if (!(label instanceof TextView)) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(selected ? accent : AppColors.inputBg());
        pill.setBackground(bg);
        ((TextView) label).setTextColor(selected ? 0xFFFFFFFF : AppColors.text1());
    }

    // ===== Search Bar =====

    private static View buildSearchBar(Context ctx) {
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(AppColors.card());
        wrapper.setPadding(dp(12), 0, dp(12), dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(AppColors.inputBg());

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(bg);
        row.setPadding(dp(12), dp(6), dp(8), dp(6));

        ImageView searchIcon = new ImageView(ctx);
        searchIcon.setImageDrawable(emojiDrawable(ctx, "\uD83D\uDD0D", dp(16)));
        searchIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.addView(searchIcon);

        sSearchBox = new EditText(ctx);
        sSearchBox.setTextSize(14);
        sSearchBox.setTextColor(AppColors.text1());
        sSearchBox.setHintTextColor(AppColors.text2());
        sSearchBox.setBackgroundColor(Color.TRANSPARENT);
        sSearchBox.setSingleLine(true);
        sSearchBox.setPadding(dp(8), 0, dp(8), 0);
        sSearchBox.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        updateSearchHint();

        sSearchBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int af) {}
            public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                setCurrentQuery(q);
                if (!q.isEmpty()) {
                    setCurrentPage(1);
                    doSearch();
                }
            }
        });

        sSearchBox.setOnEditorActionListener((v, actionId, event) -> {
            String q = sSearchBox.getText().toString().trim();
            if (!q.isEmpty()) {
                setCurrentQuery(q);
                setCurrentPage(1);
                doSearch();
                hideKeyboard(v);
            }
            return true;
        });

        row.addView(sSearchBox);

        if (sPlayer.getCurrent() != null) {
            TextView clear = new TextView(ctx);
            clear.setText("\u2715");
            clear.setTextSize(14);
            clear.setTextColor(AppColors.text2());
            clear.setPadding(dp(8), dp(2), dp(4), dp(2));
            clear.setOnClickListener(v -> {
                sSearchBox.setText("");
                clearResults();
            });
            row.addView(clear);
        }

        wrapper.addView(row);
        return wrapper;
    }

    private static void updateSearchHint() {
        if (sSearchBox != null) {
            sSearchBox.setHint(sPlatform == 0 ? "搜索酷狗音乐" : "搜索酷我音乐");
        }
    }

    private static void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) sCtx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    // ===== Results =====

    private static void showResults(List<MusicSearchApi.Song> songs, int total, boolean isLoadMore) {
        if (!isLoadMore) {
            sResultsContainer.removeAllViews();
        }

        if (songs == null || songs.isEmpty()) {
            if (!isLoadMore) {
                TextView empty = new TextView(sCtx);
                empty.setText("没有找到相关歌曲");
                empty.setTextSize(14);
                empty.setTextColor(AppColors.text2());
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, dp(40), 0, dp(40));
                sResultsContainer.addView(empty);
            }
            sLoadMoreBtn.setVisibility(View.GONE);
            return;
        }

        MusicSearchApi.Song current = sPlayer.getCurrent();
        String currentId = current != null ? current.id : null;

        for (int i = 0; i < songs.size(); i++) {
            MusicSearchApi.Song song = songs.get(i);
            sResultsContainer.addView(buildSongRow(song, song.id != null && song.id.equals(currentId)));
            if (i < songs.size() - 1) {
                sResultsContainer.addView(divider());
            }
        }

        int page = getCurrentPage();
        int pageSize = sPlatform == 0 ? 20 : 30;
        boolean hasMore = page * pageSize < total;
        sLoadMoreBtn.setVisibility(hasMore ? View.VISIBLE : View.GONE);
    }

    private static View buildSongRow(final MusicSearchApi.Song song, boolean isCurrent) {
        LinearLayout row = new LinearLayout(sCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(AppColors.card());
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        // Cover
        final ImageView cover = new ImageView(sCtx);
        int coverSize = dp(48);
        cover.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFFFFFFFF);

        GradientDrawable rounded = new GradientDrawable();
        rounded.setCornerRadius(dp(4));
        cover.setBackground(rounded);
        loadCover(cover, song.cover);

        row.addView(cover);

        LinearLayout textCol = new LinearLayout(sCtx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(10), 0, dp(8), 0);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView title = new TextView(sCtx);
        title.setText(song.title);
        title.setTextSize(15);
        title.setTextColor(isCurrent ? AppColors.accent() : AppColors.text1());
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        textCol.addView(title);

        TextView artist = new TextView(sCtx);
        artist.setText(song.artist);
        artist.setTextSize(12);
        artist.setTextColor(AppColors.text2());
        artist.setSingleLine(true);
        artist.setPadding(0, dp(2), 0, 0);
        textCol.addView(artist);

        if (song.duration > 0) {
            TextView dur = new TextView(sCtx);
            int min = song.duration / 60;
            int sec = song.duration % 60;
            dur.setText(String.format("%d:%02d", min, sec));
            dur.setTextSize(11);
            dur.setTextColor(AppColors.text2());
            dur.setPadding(0, dp(1), 0, 0);
            textCol.addView(dur);
        }

        row.addView(textCol);

        // Add to playlist button
        TextView addBtn = new TextView(sCtx);
        addBtn.setText("\u2795");
        addBtn.setTextSize(16);
        addBtn.setTextColor(AppColors.arrow());
        addBtn.setPadding(dp(8), dp(8), dp(4), dp(8));
        addBtn.setOnClickListener(v -> {
            int idx = sPlayer.getPlaylist().size();
            sPlayer.getPlaylist().add(song);
            Toast.makeText(sCtx, "已加入播放列表", Toast.LENGTH_SHORT).show();
        });
        row.addView(addBtn);

        row.setOnClickListener(v -> {
            sPlayer.play(song);
            showFullPlayer(song);
        });
        return row;
    }

    private static View divider() {
        View v = new View(sCtx);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        v.setBackgroundColor(AppColors.divider());
        return v;
    }

    // ===== Mini Player =====

    private static FrameLayout buildMiniPlayer(Context ctx) {
        sMiniPlayerBar = new FrameLayout(ctx);
        sMiniPlayerBar.setBackgroundColor(AppColors.card());
        sMiniPlayerBar.setVisibility(View.GONE);
        int h = dp(60);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, h);
        sMiniPlayerBar.setLayoutParams(lp);

        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(10), dp(6), dp(10), dp(6));

        sMiniCover = new ImageView(ctx);
        int cs = dp(44);
        sMiniCover.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        sMiniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cr = new GradientDrawable();
        cr.setCornerRadius(dp(4));
        sMiniCover.setBackground(cr);
        sMiniCover.setBackgroundColor(0xFFFFFFFF);
        inner.addView(sMiniCover);

        LinearLayout mc = new LinearLayout(ctx);
        mc.setOrientation(LinearLayout.VERTICAL);
        mc.setPadding(dp(10), 0, dp(8), 0);
        mc.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        sMiniTitle = new TextView(ctx);
        sMiniTitle.setTextSize(14);
        sMiniTitle.setTextColor(AppColors.text1());
        sMiniTitle.setSingleLine(true);
        mc.addView(sMiniTitle);

        sMiniTime = new TextView(ctx);
        sMiniTime.setTextSize(11);
        sMiniTime.setTextColor(AppColors.text2());
        mc.addView(sMiniTime);

        inner.addView(mc);

        sMiniPlayBtn = new ImageView(ctx);
        int bs = dp(36);
        sMiniPlayBtn.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        sMiniPlayBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        updatePlayBtnIcon();
        sMiniPlayBtn.setOnClickListener(v -> sPlayer.togglePause());
        inner.addView(sMiniPlayBtn);

        ImageView nextBtn = new ImageView(ctx);
        nextBtn.setLayoutParams(new LinearLayout.LayoutParams(bs, bs));
        nextBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        nextBtn.setImageDrawable(emojiDrawable(ctx, "\u23ED", dp(18)));
        nextBtn.setOnClickListener(v -> sPlayer.next());
        inner.addView(nextBtn);

        sMiniPlayerBar.addView(inner);

        sMiniPlayerBar.setOnClickListener(v -> {
            if (sPlayer.getCurrent() != null) {
                showFullPlayer(sPlayer.getCurrent());
            }
        });

        return sMiniPlayerBar;
    }

    static FrameLayout getMiniBar() {
        return sMiniPlayerBar;
    }

    static void updateMiniPlayer() {
        if (sMiniPlayerBar == null) return;
        MusicSearchApi.Song song = sPlayer.getCurrent();
        if (song == null) {
            sMiniPlayerBar.setVisibility(View.GONE);
            return;
        }
        sMiniPlayerBar.setVisibility(View.VISIBLE);
        sMiniTitle.setText(song.title + " - " + song.artist);
        updatePlayBtnIcon();

        int pos = sPlayer.getPosition();
        int dur = sPlayer.getDuration();
        if (dur > 0) {
            sMiniTime.setText(formatTime(pos) + " / " + formatTime(dur));
        } else {
            sMiniTime.setText("");
        }

        if (!song.cover.equals(sLastCoverUrl)) {
            sLastCoverUrl = song.cover;
            loadCover(sMiniCover, song.cover);
        }
    }

    private static void updatePlayBtnIcon() {
        if (sMiniPlayBtn == null) return;
        boolean playing = sPlayer.isPlaying();
        Drawable icon = emojiDrawable(sCtx, playing ? "\u23F8" : "\u25B6", dp(20));
        sMiniPlayBtn.setImageDrawable(icon);
    }

    static void updatePlayButtonState(boolean playing) {
        updatePlayBtnIcon();
    }

    static String formatTime(int ms) {
        int totalSec = ms / 1000;
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format("%d:%02d", min, sec);
    }

    // ===== Full Player Dialog =====

    private static void showFullPlayer(MusicSearchApi.Song song) {
        AlertDialog dialog = new AlertDialog.Builder(sCtx, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
            .setCancelable(true)
            .create();
        View playerView = MusicPlayerView.create(sCtx, sActivity, song, dialog);
        dialog.setView(playerView);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(AppColors.bg()));
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
    }

    // ===== Search Logic =====

    private static void doSearch() {
        String query = getCurrentQuery();
        int page = getCurrentPage();
        if (query.isEmpty()) return;

        boolean isLoadMore = page > 1;
        if (!isLoadMore) showLoading();
        else sLoadMoreBtn.setText("加载中...");

        final boolean fIsLoadMore = isLoadMore;
        MusicSearchApi.SearchCallback cb = new MusicSearchApi.SearchCallback() {
            @Override
            public void onResult(List<MusicSearchApi.Song> songs, int total, boolean hasPrev, boolean hasNext) {
                setHasPrev(hasPrev);
                setHasNext(hasNext);
                if (fIsLoadMore) {
                    List<MusicSearchApi.Song> existing = getCurrentResults();
                    existing.addAll(songs);
                    saveCurrentResults(existing);
                } else {
                    saveCurrentResults(songs);
                }
                showResults(songs, total, fIsLoadMore);
                sH.postDelayed(() -> updateMiniPlayer(), 100);
            }
            @Override
            public void onError(String msg) {
                sH.post(() -> {
                    if (fIsLoadMore) sLoadMoreBtn.setText("加载更多");
                    Toast.makeText(sCtx, msg, Toast.LENGTH_SHORT).show();
                });
            }
        };

        if (sPlatform == 0) {
            MusicSearchApi.searchKugou(query, page, cb);
        } else {
            MusicSearchApi.searchKuwo(query, page, cb);
        }
    }

    private static void showLoading() {
        sResultsContainer.removeAllViews();
        sLoadMoreBtn.setVisibility(View.GONE);
        LinearLayout ll = new LinearLayout(sCtx);
        ll.setGravity(Gravity.CENTER);
        ll.setPadding(0, dp(40), 0, dp(40));
        ProgressBar pb = new ProgressBar(sCtx);
        pb.setIndeterminate(true);
        ll.addView(pb);
        sResultsContainer.addView(ll);
    }

    private static void clearResults() {
        sResultsContainer.removeAllViews();
        sLoadMoreBtn.setVisibility(View.GONE);
    }

    private static void loadCurrentTab() {
        String q = getCurrentQuery();
        if (q.isEmpty()) {
            List<MusicSearchApi.Song> cached = getCurrentResults();
            if (!cached.isEmpty()) {
                showResults(cached, cached.size(), false);
            } else {
                clearResults();
            }
            sH.postDelayed(() -> updateMiniPlayer(), 100);
        } else {
            doSearch();
        }
    }

    // ===== State accessors =====

    private static String getCurrentQuery() { return sPlatform == 0 ? sKgQuery : sKwQuery; }
    private static void setCurrentQuery(String q) {
        if (sPlatform == 0) sKgQuery = q; else sKwQuery = q;
    }
    private static int getCurrentPage() { return sPlatform == 0 ? sKgPage : sKwPage; }
    private static void setCurrentPage(int p) {
        if (sPlatform == 0) sKgPage = p; else sKwPage = p;
    }
    private static void setHasPrev(boolean v) {
        if (sPlatform == 0) sKgHasPrev = v; else sKwHasPrev = v;
    }
    private static void setHasNext(boolean v) {
        if (sPlatform == 0) sKgHasNext = v; else sKwHasNext = v;
    }
    private static List<MusicSearchApi.Song> getCurrentResults() {
        return sPlatform == 0 ? sKgResults : sKwResults;
    }
    private static void saveCurrentResults(List<MusicSearchApi.Song> list) {
        if (sPlatform == 0) sKgResults = list; else sKwResults = list;
    }

    // ===== Cover loading =====

    private static void loadCover(ImageView iv, String url) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) return;
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(url.replace("{size}", "150"));
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                java.io.InputStream is = conn.getInputStream();
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();
                if (bmp != null) {
                    sH.post(() -> iv.setImageBitmap(bmp));
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    // ===== Utilities =====

    private static int dp(int dp) {
        return (int) (dp * sDensity + 0.5f);
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
