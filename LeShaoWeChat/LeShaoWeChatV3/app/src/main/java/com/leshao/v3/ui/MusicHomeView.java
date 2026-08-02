package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class MusicHomeView {

    private Activity mActivity;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private ProgressBar mRankLoading;
    private HorizontalScrollView mRankScroll;
    private LinearLayout mRankCards;

    private ProgressBar mPlaylistLoading;
    private LinearLayout mPlaylistGrid;

    private ProgressBar mArtistLoading;
    private LinearLayout mArtistGrid;
    private LinearLayout mSearchResults;
    private Runnable mSearchDebounce;
    private EditText mSearchInput;
    private LinearLayout mRootView;

    private ScrollView mSearchScroll;
    private LinearLayout mSearchPageList;
    private TextView mSearchLoadMore;
    private int mSearchPageNum = 0;
    private String mSearchKeyword = "";
    private int mSearchTotal = 0;
    private boolean mSearchLoading = false;

    private ProgressBar mRandomLoading;
    private LinearLayout mRandomList;
    private HorizontalScrollView mRandomScroll;
    private int mRandomPage = 0;
    private final List<KgApi.Song> mRandomAllSongs = new ArrayList<>();
    private boolean mRandomLoadingMore = false;

    private static final int[] CARD_COLORS = {
        0xFF3B8EFF, 0xFFF59E0B, 0xFFEF4444, 0xFF10B981, 0xFF8B5CF6
    };

    private ScrollView mHomeScroll;
    private FrameLayout mSearchPage;

    private List<String> mSearchHistory = new ArrayList<>();
    private LinearLayout mHomeRandomList;
    private HorizontalScrollView mHomeRandomScroll;
    private int mHomeRandomPage = 0;
    private boolean mHomeRandomLoading = false;

    private ProgressBar mRandPlaylistLoading;
    private LinearLayout mRandPlaylistGrid;

    public View createView(Activity activity) {
        mActivity = activity;

        FrameLayout container = new FrameLayout(mActivity);
        container.setBackgroundColor(MusicActivity.CLR_BG);

        mHomeScroll = new ScrollView(mActivity);
        mHomeScroll.setFillViewport(true);
        mHomeScroll.setBackgroundColor(MusicActivity.CLR_BG);

        mRootView = new LinearLayout(mActivity);
        mRootView.setOrientation(LinearLayout.VERTICAL);
        mRootView.setPadding(MusicActivity.dp(12), MusicActivity.sStatusBarH + MusicActivity.dp(8),
                MusicActivity.dp(12), MusicActivity.dp(8));

        buildHeader(mRootView);
        buildSearchBar(mRootView);
        buildHomeRandomSection(mRootView);
        buildRandomPlaylistSection(mRootView);
        buildRankingSection(mRootView);
        buildArtistSection(mRootView);

        mHomeScroll.addView(mRootView);
        container.addView(mHomeScroll);

        return container;
    }

    public void onViewReady() {
        loadData();
    }

    public boolean isSearchPageVisible() {
        return mSearchPage != null && mHomeScroll != null && mHomeScroll.getVisibility() == View.GONE;
    }

    public boolean hideSearchIfShown() {
        if (isSearchPageVisible()) {
            hideSearchPage();
            return true;
        }
        return false;
    }

    private void buildHeader(LinearLayout parent) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, MusicActivity.dp(4), 0, MusicActivity.dp(4));

        TextView title = new TextView(mActivity);
        title.setText("\u4E50\u5C11\u97F3\u4E50");
        title.setTextSize(20);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(title);

        TextView importBtn = new TextView(mActivity);
        importBtn.setText("\uD83D\uDCCB");
        importBtn.setTextSize(16);
        importBtn.setTextColor(MusicActivity.CLR_ACCENT);
        importBtn.setGravity(Gravity.CENTER);
        importBtn.setPadding(MusicActivity.dp(10), MusicActivity.dp(5), MusicActivity.dp(10), MusicActivity.dp(5));
        importBtn.setBackground(MusicActivity.rd(14, MusicActivity.CLR_ACCENT_LIGHT));
        importBtn.setOnClickListener(v -> showImportDialog());
        row.addView(importBtn);

        parent.addView(row);
    }

    private void buildSearchBar(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(MusicActivity.rd(18, MusicActivity.CLR_CARD));
        bar.setPadding(MusicActivity.dp(10), 0, MusicActivity.dp(4), 0);

        mSearchInput = new EditText(mActivity);
        mSearchInput.setHint("搜索歌曲/歌手/专辑");
        mSearchInput.setTextSize(13);
        mSearchInput.setTextColor(MusicActivity.CLR_TEXT);
        mSearchInput.setHintTextColor(MusicActivity.CLR_TEXT2);
        mSearchInput.setBackground(null);
        mSearchInput.setSingleLine(true);
        mSearchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mSearchInput.setFocusable(false);
        mSearchInput.setFocusableInTouchMode(false);
        mSearchInput.setCursorVisible(false);
        mSearchInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String kw = mSearchInput.getText().toString().trim();
                if (!kw.isEmpty()) {
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) mActivity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(mSearchInput.getWindowToken(), 0);
                    addSearchHistory(kw);
                    showSearchPage(kw);
                }
                return true;
            }
            return false;
        });
        bar.addView(mSearchInput);

        bar.setOnClickListener(v -> showSearchInputDialog());
        mSearchInput.setOnClickListener(v -> showSearchInputDialog());

        parent.addView(bar);
    }

    private void showSearchInputDialog() {
        if (!mSearchInput.isFocusable()) {
            mSearchInput.setFocusable(true);
            mSearchInput.setFocusableInTouchMode(true);
            mSearchInput.setCursorVisible(true);
        }
        mSearchInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) mActivity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mSearchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);

        showSearchHistoryPopup();
    }

    private void showSearchHistoryPopup() {
        if (mSearchHistory.isEmpty()) return;

        final PopupWindow popup = new PopupWindow(mActivity);
        LinearLayout listView = new LinearLayout(mActivity);
        listView.setOrientation(LinearLayout.VERTICAL);
        listView.setBackground(MusicActivity.rd(10, MusicActivity.CLR_CARD));
        int pad = MusicActivity.dp(8);
        listView.setPadding(pad, pad, pad, pad);

        TextView clearBtn = new TextView(mActivity);
        clearBtn.setText("清除历史");
        clearBtn.setTextSize(11);
        clearBtn.setTextColor(MusicActivity.CLR_ACCENT);
        clearBtn.setGravity(Gravity.CENTER);
        clearBtn.setPadding(0, 0, 0, MusicActivity.dp(4));
        clearBtn.setOnClickListener(v -> {
            mSearchHistory.clear();
            saveSearchHistory();
            popup.dismiss();
        });
        listView.addView(clearBtn);

        int maxShow = Math.min(mSearchHistory.size(), 8);
        for (int i = maxShow - 1; i >= 0; i--) {
            final String kw = mSearchHistory.get(i);
            TextView item = new TextView(mActivity);
            item.setText(kw);
            item.setTextSize(12);
            item.setTextColor(MusicActivity.CLR_TEXT);
            item.setPadding(0, MusicActivity.dp(4), 0, MusicActivity.dp(4));
            item.setOnClickListener(v -> {
                popup.dismiss();
                mSearchInput.setText(kw);
                addSearchHistory(kw);
                showSearchPage(kw);
            });
            listView.addView(item);
        }

        popup.setContentView(listView);
        popup.setWidth(mSearchInput.getWidth() > 0 ? mSearchInput.getWidth() : MusicActivity.dp(260));
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.showAsDropDown(mSearchInput, 0, MusicActivity.dp(4));
    }

    private void loadSearchHistory() {
        SharedPreferences sp = mActivity.getSharedPreferences("music_search_history", 0);
        Set<String> set = sp.getStringSet("history", new HashSet<>());
        mSearchHistory.clear();
        mSearchHistory.addAll(set);
    }

    private void addSearchHistory(String kw) {
        mSearchHistory.remove(kw);
        mSearchHistory.add(kw);
        while (mSearchHistory.size() > 20) mSearchHistory.remove(0);
        saveSearchHistory();
    }

    private void saveSearchHistory() {
        SharedPreferences sp = mActivity.getSharedPreferences("music_search_history", 0);
        sp.edit().putStringSet("history", new HashSet<>(mSearchHistory)).apply();
    }

    private void buildHomeRandomSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(8);
        sectionLp.topMargin = MusicActivity.dp(12);
        section.setLayoutParams(sectionLp);
        section.addView(buildSectionTitle("随机推荐"));

        mHomeRandomScroll = new HorizontalScrollView(mActivity);
        mHomeRandomScroll.setHorizontalScrollBarEnabled(false);
        mHomeRandomScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        mHomeRandomList = new LinearLayout(mActivity);
        mHomeRandomList.setOrientation(LinearLayout.HORIZONTAL);
        mHomeRandomScroll.addView(mHomeRandomList);

        mHomeRandomScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (mHomeRandomList == null || mHomeRandomLoading) return;
            int totalWidth = mHomeRandomList.getWidth();
            int viewWidth = mHomeRandomScroll.getWidth();
            if (totalWidth <= 0 || viewWidth <= 0) return;
            if (scrollX + viewWidth >= totalWidth - MusicActivity.dp(4)) {
                mHomeRandomLoading = true;
                loadHomeRandomSongs();
            }
        });
        section.addView(mHomeRandomScroll);

        parent.addView(section);
    }

    private void loadHomeRandomSongs() {
        if (mHomeRandomLoading) return;
        mHomeRandomLoading = true;
        final int page = mHomeRandomPage + 1;
        mHomeRandomPage = page;
        KgApi.getTopListDetail("8888", page, new KgApi.PlaylistSongsCallback() {
            public void onResult(List<KgApi.Song> songs, int total) {
                mHandler.post(() -> {
                    mHomeRandomLoading = false;
                    if (songs == null || songs.isEmpty()) return;
                    List<KgApi.Song> pick = new ArrayList<>(songs);
                    Collections.shuffle(pick, new Random());
                    int need = Math.min(pick.size(), 15);
                    List<KgApi.Song> batch = pick.subList(0, need);
                    populateHomeRandom(batch);
                });
            }
            public void onError(String msg) {
                mHandler.post(() -> { mHomeRandomLoading = false; });
            }
        });
    }

    private void populateHomeRandom(List<KgApi.Song> songs) {
        if (songs == null || songs.isEmpty()) return;

        int colW = MusicActivity.dp(170);
        LinearLayout col = new LinearLayout(mActivity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(MusicActivity.CLR_CARD);
        col.setPadding(MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4));

        for (KgApi.Song ks : songs) {
            MusicSearchApi.Song ms = convertSingle(ks);

            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(MusicActivity.dp(4), MusicActivity.dp(3), MusicActivity.dp(4), MusicActivity.dp(3));

            ImageView cov = new ImageView(mActivity);
            cov.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(36), MusicActivity.dp(36)));
            if (ks.cover != null && !ks.cover.isEmpty()) {
                MusicActivity.loadCircularCover(cov, ks.cover);
            } else {
                loadCoverFallback(cov, ks.title);
            }
            row.addView(cov);

            LinearLayout info = new LinearLayout(mActivity);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(MusicActivity.dp(6), 0, MusicActivity.dp(4), 0);

            TextView nameTv = new TextView(mActivity);
            nameTv.setText(ks.title != null ? ks.title : "");
            nameTv.setTextSize(11);
            nameTv.setTextColor(MusicActivity.CLR_TEXT);
            nameTv.setSingleLine(true);
            nameTv.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(nameTv);

            TextView artistTv = new TextView(mActivity);
            artistTv.setText(ks.artist != null ? ks.artist : "");
            artistTv.setTextSize(9);
            artistTv.setTextColor(MusicActivity.CLR_TEXT2);
            artistTv.setSingleLine(true);
            artistTv.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(artistTv);

            info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
            row.addView(info);

            TextView playBtn = new TextView(mActivity);
            playBtn.setText("\u25B6");
            playBtn.setTextSize(14);
            playBtn.setTextColor(0xFFFFFFFF);
            playBtn.setGravity(Gravity.CENTER);
            playBtn.setBackground(MusicActivity.rd(14, MusicActivity.CLR_ACCENT));
            playBtn.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(28), MusicActivity.dp(28)));
            final MusicSearchApi.Song fms = ms;
            row.setOnClickListener(v -> {
                try {
                    MusicActivity.playSong(fms);
                } catch (Throwable e) {
                    MusicActivity.toast("播放失败");
                }
            });
            row.addView(playBtn);

            col.addView(row);
        }

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(colW, -2);
        colLp.rightMargin = MusicActivity.dp(8);
        mHomeRandomList.addView(col, colLp);
    }

    private void buildRandomPlaylistSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(8);
        section.setLayoutParams(sectionLp);

        LinearLayout titleRow = new LinearLayout(mActivity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, MusicActivity.dp(6), 0, MusicActivity.dp(2));

        View bar = new View(mActivity);
        bar.setBackgroundColor(MusicActivity.CLR_ACCENT);
        bar.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(3), MusicActivity.dp(14)));
        titleRow.addView(bar);

        TextView title = new TextView(mActivity);
        title.setText("随机歌单");
        title.setTextSize(14);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(MusicActivity.dp(6), 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        titleRow.addView(title);

        TextView refreshBtn = new TextView(mActivity);
        refreshBtn.setText("\uD83D\uDD04 换一批");
        refreshBtn.setTextSize(11);
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setPadding(MusicActivity.dp(8), MusicActivity.dp(4), MusicActivity.dp(8), MusicActivity.dp(4));
        refreshBtn.setBackground(MusicActivity.rd(12, MusicActivity.CLR_ACCENT));
        refreshBtn.setOnClickListener(v -> {
            mRandPlaylistLoading.setVisibility(View.VISIBLE);
            mRandPlaylistGrid.setVisibility(View.GONE);
            mRandPlaylistGrid.removeAllViews();
            loadRandomPlaylists();
        });
        titleRow.addView(refreshBtn);
        section.addView(titleRow);

        mRandPlaylistLoading = new ProgressBar(mActivity);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = MusicActivity.dp(8);
        mRandPlaylistLoading.setLayoutParams(llp);
        section.addView(mRandPlaylistLoading);

        mRandPlaylistGrid = new LinearLayout(mActivity);
        mRandPlaylistGrid.setOrientation(LinearLayout.VERTICAL);
        mRandPlaylistGrid.setVisibility(View.GONE);
        section.addView(mRandPlaylistGrid);

        parent.addView(section);
    }

    private void loadRandomPlaylists() {
        KgApi.getRecommendedPlaylists(new KgApi.PlaylistCallback() {
            public void onResult(List<KgApi.Playlist> playlists) {
                mHandler.post(() -> {
                    mRandPlaylistLoading.setVisibility(View.GONE);
                    if (playlists == null || playlists.isEmpty()) return;
                    List<KgApi.Playlist> shuffled = new ArrayList<>(playlists);
                    Collections.shuffle(shuffled, new Random());
                    int max = Math.min(shuffled.size(), 24);
                    populateRandomPlaylists(shuffled.subList(0, max));
                });
            }
            public void onError(String msg) {
                mHandler.post(() -> mRandPlaylistLoading.setVisibility(View.GONE));
            }
        });
    }

    private void populateRandomPlaylists(List<KgApi.Playlist> list) {
        mRandPlaylistGrid.setVisibility(View.VISIBLE);
        mRandPlaylistGrid.removeAllViews();

        int itemsPerRow = 4;
        int gap = MusicActivity.dp(4);
        int cardH = MusicActivity.dp(48);

        for (int i = 0; i < list.size(); i += itemsPerRow) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rowLp.topMargin = gap;
            row.setLayoutParams(rowLp);

            for (int j = 0; j < itemsPerRow; j++) {
                int index = i + j;
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, cardH, 1.0f);
                if (j == 0) cardLp.rightMargin = gap / 2;
                else if (j == itemsPerRow - 1) cardLp.leftMargin = gap / 2;
                else { cardLp.leftMargin = gap / 2; cardLp.rightMargin = gap / 2; }

                if (index < list.size())
                    row.addView(buildPlaylistCard(list.get(index)), cardLp);
                else
                    row.addView(new View(mActivity), cardLp);
            }
            mRandPlaylistGrid.addView(row);
        }
    }

    private void buildRankingSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(8);
        section.setLayoutParams(sectionLp);
        section.addView(buildSectionTitle("\u70ED\u95E8\u6392\u884C"));

        mRankLoading = new ProgressBar(mActivity);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = MusicActivity.dp(8);
        mRankLoading.setLayoutParams(llp);
        section.addView(mRankLoading);

        mRankScroll = new HorizontalScrollView(mActivity);
        mRankScroll.setHorizontalScrollBarEnabled(false);
        mRankScroll.setVisibility(View.GONE);
        mRankScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        mRankCards = new LinearLayout(mActivity);
        mRankCards.setOrientation(LinearLayout.HORIZONTAL);
        mRankCards.setPadding(0, MusicActivity.dp(2), 0, 0);
        mRankScroll.addView(mRankCards);
        section.addView(mRankScroll);

        parent.addView(section);
    }

    private void buildRandomSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(8);
        section.setLayoutParams(sectionLp);

        LinearLayout titleRow = new LinearLayout(mActivity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, MusicActivity.dp(6), 0, MusicActivity.dp(2));

        View bar = new View(mActivity);
        bar.setBackgroundColor(MusicActivity.CLR_ACCENT);
        bar.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(3), MusicActivity.dp(14)));
        titleRow.addView(bar);

        TextView title = new TextView(mActivity);
        title.setText("\u968F\u673A\u63A8\u8350");
        title.setTextSize(14);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(MusicActivity.dp(6), 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        titleRow.addView(title);

        TextView refreshBtn = new TextView(mActivity);
        refreshBtn.setText("\uD83D\uDD04 \u6362\u4E00\u6279");
        refreshBtn.setTextSize(11);
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setPadding(MusicActivity.dp(8), MusicActivity.dp(4), MusicActivity.dp(8), MusicActivity.dp(4));
        refreshBtn.setBackground(MusicActivity.rd(12, MusicActivity.CLR_ACCENT));
        refreshBtn.setOnClickListener(v -> {
            mRandomPage = 0;
            mRandomAllSongs.clear();
            mRandomLoading.setVisibility(View.VISIBLE);
            mRandomList.setVisibility(View.GONE);
            loadRandomSongs();
        });
        titleRow.addView(refreshBtn);
        section.addView(titleRow);

        mRandomLoading = new ProgressBar(mActivity);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = MusicActivity.dp(8);
        mRandomLoading.setLayoutParams(llp);
        section.addView(mRandomLoading);

        mRandomScroll = new HorizontalScrollView(mActivity);
        mRandomScroll.setHorizontalScrollBarEnabled(false);
        mRandomScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        mRandomList = new LinearLayout(mActivity);
        mRandomList.setOrientation(LinearLayout.HORIZONTAL);
        mRandomList.setVisibility(View.GONE);
        mRandomScroll.addView(mRandomList);

        mRandomScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (mRandomList == null || mRandomLoadingMore) return;
            int totalWidth = mRandomList.getWidth();
            int viewWidth = mRandomScroll.getWidth();
            if (totalWidth <= 0 || viewWidth <= 0) return;
            if (scrollX + viewWidth >= totalWidth - MusicActivity.dp(4)) {
                mRandomLoadingMore = true;
                loadRandomSongs();
            }
        });
        section.addView(mRandomScroll);

        parent.addView(section);
    }

    private void buildPlaylistSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(8);
        section.setLayoutParams(sectionLp);
        section.addView(buildSectionTitle("\u63A8\u8350\u6B4C\u5355"));

        mPlaylistLoading = new ProgressBar(mActivity);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = MusicActivity.dp(8);
        mPlaylistLoading.setLayoutParams(llp);
        section.addView(mPlaylistLoading);

        mPlaylistGrid = new LinearLayout(mActivity);
        mPlaylistGrid.setOrientation(LinearLayout.VERTICAL);
        mPlaylistGrid.setVisibility(View.GONE);
        section.addView(mPlaylistGrid);

        parent.addView(section);
    }

    private void buildArtistSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(mActivity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, MusicActivity.dp(6), 0, MusicActivity.dp(2));

        View bar = new View(mActivity);
        bar.setBackgroundColor(MusicActivity.CLR_ACCENT);
        bar.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(3), MusicActivity.dp(14)));
        titleRow.addView(bar);

        TextView title = new TextView(mActivity);
        title.setText("\u70ED\u95E8\u6B4C\u624B");
        title.setTextSize(14);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(MusicActivity.dp(6), 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        titleRow.addView(title);

        TextView refreshArtists = new TextView(mActivity);
        refreshArtists.setText("\uD83D\uDD04 \u6362\u4E00\u6279");
        refreshArtists.setTextSize(11);
        refreshArtists.setTextColor(0xFFFFFFFF);
        refreshArtists.setGravity(Gravity.CENTER);
        refreshArtists.setPadding(MusicActivity.dp(8), MusicActivity.dp(4), MusicActivity.dp(8), MusicActivity.dp(4));
        refreshArtists.setBackground(MusicActivity.rd(12, MusicActivity.CLR_ACCENT));
        refreshArtists.setOnClickListener(v -> {
            mArtistLoading.setVisibility(View.VISIBLE);
            mArtistGrid.setVisibility(View.GONE);
            loadArtistsRandom();
        });
        titleRow.addView(refreshArtists);
        section.addView(titleRow);

        mArtistLoading = new ProgressBar(mActivity);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = MusicActivity.dp(8);
        mArtistLoading.setLayoutParams(llp);
        section.addView(mArtistLoading);

        mArtistGrid = new LinearLayout(mActivity);
        mArtistGrid.setOrientation(LinearLayout.VERTICAL);
        mArtistGrid.setVisibility(View.GONE);
        section.addView(mArtistGrid);

        parent.addView(section);
    }

    private LinearLayout buildSectionTitle(String titleText) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, MusicActivity.dp(8), 0, MusicActivity.dp(4));

        View bar = new View(mActivity);
        bar.setBackgroundColor(MusicActivity.CLR_ACCENT);
        bar.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(3), MusicActivity.dp(14)));
        row.addView(bar);

        TextView title = new TextView(mActivity);
        title.setText(titleText);
        title.setTextSize(14);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(MusicActivity.dp(6), 0, 0, 0);
        row.addView(title);

        return row;
    }

    private void loadData() {
        loadSearchHistory();

        KgApi.getTopLists(new KgApi.RankingCallback() {
            public void onResult(List<KgApi.Ranking> list) {
                mHandler.post(() -> populateRankings(list));
            }
            public void onError(String msg) {
                mHandler.post(() -> mRankLoading.setVisibility(View.GONE));
            }
        });

        loadHomeRandomSongs();
        loadRandomPlaylists();

        KgApi.getHotArtists(new KgApi.PlaylistCallback() {
            public void onResult(List<KgApi.Playlist> artists) {
                mHandler.post(() -> populateArtists(artists));
            }
        });
    }

    private boolean mLoadingArtists = false;

    private void loadArtistsRandom() {
        if (mLoadingArtists) return;
        mLoadingArtists = true;
        KgApi.getHotArtists(new KgApi.PlaylistCallback() {
            public void onResult(List<KgApi.Playlist> artists) {
                mHandler.post(() -> {
                    try {
                        if (mActivity == null || artists == null || artists.isEmpty()) {
                            mArtistLoading.setVisibility(View.GONE);
                            mLoadingArtists = false;
                            return;
                        }
                        List<KgApi.Playlist> copy = new ArrayList<>(artists);
                        Collections.shuffle(copy, new Random());
                        populateArtists(copy);
                    } catch (Throwable e) {
                        mArtistLoading.setVisibility(View.GONE);
                    }
                    mLoadingArtists = false;
                });
            }
            public void onError(String msg) {
                mHandler.post(() -> {
                    mArtistLoading.setVisibility(View.GONE);
                    mLoadingArtists = false;
                });
            }
        });
    }

    private void populateRankings(List<KgApi.Ranking> list) {
        mRankLoading.setVisibility(View.GONE);
        if (list == null || list.isEmpty()) return;
        mRankScroll.setVisibility(View.VISIBLE);
        mRankCards.removeAllViews();

        int count = Math.min(list.size(), 5);
        int cardW = MusicActivity.dp(78);
        int cardH = MusicActivity.dp(96);
        int gap = MusicActivity.dp(6);

        for (int i = 0; i < count; i++) {
            KgApi.Ranking item = list.get(i);
            FrameLayout card = new FrameLayout(mActivity);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) cardLp.leftMargin = gap;
            card.setLayoutParams(cardLp);
            card.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));

            LinearLayout inner = new LinearLayout(mActivity);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER_HORIZONTAL);
            inner.setPadding(MusicActivity.dp(6), MusicActivity.dp(8), MusicActivity.dp(6), MusicActivity.dp(6));
            inner.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

            FrameLayout colorBox = new FrameLayout(mActivity);
            int boxSize = MusicActivity.dp(36);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(boxSize, boxSize);
            boxLp.gravity = Gravity.CENTER_HORIZONTAL;
            boxLp.bottomMargin = MusicActivity.dp(6);
            colorBox.setLayoutParams(boxLp);
            GradientDrawable boxBg = new GradientDrawable();
            boxBg.setCornerRadius(MusicActivity.dp(5));
            boxBg.setColor(CARD_COLORS[i % CARD_COLORS.length]);
            colorBox.setBackground(boxBg);

            TextView rankNum = new TextView(mActivity);
            rankNum.setText(String.valueOf(i + 1));
            rankNum.setTextSize(16);
            rankNum.setTextColor(0xFFFFFFFF);
            rankNum.setGravity(Gravity.CENTER);
            rankNum.setTypeface(null, Typeface.BOLD);
            colorBox.addView(rankNum, new FrameLayout.LayoutParams(-1, -1));
            inner.addView(colorBox);

            TextView name = new TextView(mActivity);
            String rTitle = item.title != null ? item.title : "";
            name.setText(rTitle.length() > 4 ? rTitle.substring(0, 4) : rTitle);
            name.setTextSize(10);
            name.setTextColor(MusicActivity.CLR_TEXT);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            inner.addView(name);

            card.addView(inner);
            card.setOnClickListener(v -> {
                if (MusicActivity.sInstance != null) MusicActivity.sInstance.showTab(1);
            });
            mRankCards.addView(card);
        }
    }

    private void loadRandomSongs() {
        final int page = mRandomPage + 1;
        mRandomPage = page;
        KgApi.getTopListDetail("8888", page, new KgApi.PlaylistSongsCallback() {
            public void onResult(List<KgApi.Song> songs, int total) {
                mHandler.post(() -> {
                    if (songs == null || songs.isEmpty()) {
                        mRandomLoading.setVisibility(View.GONE);
                        mRandomLoadingMore = false;
                        return;
                    }
                    List<KgApi.Song> fresh = new ArrayList<>();
                    for (KgApi.Song s : songs) {
                        boolean dup = false;
                        for (KgApi.Song cur : mRandomAllSongs) {
                            if (cur.hash != null && cur.hash.equals(s.hash)) { dup = true; break; }
                        }
                        if (!dup) fresh.add(s);
                    }
                    mRandomAllSongs.addAll(fresh);
                    Random rng = new Random();
                    List<KgApi.Song> batch = new ArrayList<>();
                    int need = 15;
                    while (batch.size() < need && batch.size() < fresh.size()) {
                        KgApi.Song s = fresh.get(rng.nextInt(fresh.size()));
                        if (!batch.contains(s)) batch.add(s);
                    }
                    populateRandom(batch);
                });
            }
            public void onError(String msg) {
                mHandler.post(() -> {
                    mRandomLoading.setVisibility(View.GONE);
                    mRandomLoadingMore = false;
                });
            }
        });
    }

    private void populateRandom(List<KgApi.Song> songs) {
        mRandomLoading.setVisibility(View.GONE);
        mRandomLoadingMore = false;

        if (songs == null || songs.isEmpty()) return;

        mRandomList.setVisibility(View.VISIBLE);

        int colW = MusicActivity.dp(170);
        LinearLayout col = new LinearLayout(mActivity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(MusicActivity.CLR_CARD);
        col.setPadding(MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4));

        for (KgApi.Song ks : songs) {
            MusicSearchApi.Song ms = convertSingle(ks);

            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(MusicActivity.dp(4), MusicActivity.dp(3), MusicActivity.dp(4), MusicActivity.dp(3));

            ImageView cov = new ImageView(mActivity);
            cov.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(36), MusicActivity.dp(36)));
            if (ks.cover != null && !ks.cover.isEmpty()) {
                MusicActivity.loadCircularCover(cov, ks.cover);
            } else {
                loadCoverFallback(cov, ks.title);
            }
            row.addView(cov);

            LinearLayout info = new LinearLayout(mActivity);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(MusicActivity.dp(6), 0, MusicActivity.dp(4), 0);

            TextView nameTv = new TextView(mActivity);
            nameTv.setText(ks.title != null ? ks.title : "");
            nameTv.setTextSize(11);
            nameTv.setTextColor(MusicActivity.CLR_TEXT);
            nameTv.setSingleLine(true);
            nameTv.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(nameTv);

            TextView artistTv = new TextView(mActivity);
            artistTv.setText(ks.artist != null ? ks.artist : "");
            artistTv.setTextSize(9);
            artistTv.setTextColor(MusicActivity.CLR_TEXT2);
            artistTv.setSingleLine(true);
            artistTv.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(artistTv);

            info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
            row.addView(info);

            TextView playBtn = new TextView(mActivity);
            playBtn.setText("\u25B6");
            playBtn.setTextSize(14);
            playBtn.setTextColor(0xFFFFFFFF);
            playBtn.setGravity(Gravity.CENTER);
            playBtn.setBackground(MusicActivity.rd(14, MusicActivity.CLR_ACCENT));
            playBtn.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(28), MusicActivity.dp(28)));
            final MusicSearchApi.Song fms = ms;
            row.setOnClickListener(v -> {
                try {
                    MusicActivity.playSong(fms);
                } catch (Throwable e) {
                    MusicActivity.toast("播放失败");
                }
            });
            row.addView(playBtn);

            col.addView(row);
        }

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(colW, -2);
        colLp.rightMargin = MusicActivity.dp(8);
        mRandomList.addView(col, colLp);
    }

    private void showImportDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);
        builder.setTitle("导入酷狗歌单");

        final EditText input = new EditText(mActivity);
        input.setHint("粘贴酷狗歌单链接 (如 https://www.kugou.com/special/... )");
        input.setTextSize(13);
        input.setSingleLine(true);
        input.setPadding(MusicActivity.dp(12), MusicActivity.dp(10), MusicActivity.dp(12), MusicActivity.dp(10));
        builder.setView(input);

        builder.setPositiveButton("导入", (d, which) -> {
            String url = input.getText().toString().trim();
            if (url.isEmpty()) {
                MusicActivity.toast("请输入链接");
                return;
            }
            importPlaylist(url);
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void importPlaylist(String url) {
        String specialId = null;
        if (url.contains("special/")) {
            int idx = url.lastIndexOf("special/");
            String after = url.substring(idx + 8);
            int end = after.indexOf("?");
            if (end < 0) end = after.indexOf("#");
            if (end >= 0) after = after.substring(0, end);
            specialId = after.trim();
        } else if (url.contains("specialid=") || url.contains("special_id=")) {
            String[] params = url.split("[?&]");
            for (String p : params) {
                if (p.startsWith("specialid=") || p.startsWith("special_id=")) {
                    specialId = p.substring(p.indexOf('=') + 1);
                    break;
                }
            }
        } else {
            specialId = url.trim();
        }

        if (TextUtils.isEmpty(specialId)) {
            MusicActivity.toast("无法解析歌单ID，请检查链接格式");
            return;
        }

        ProgressBar pb = new ProgressBar(mActivity);
        pb.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));

        MusicActivity.toast("正在加载歌单...");
        KgApi.getPlaylistDetail(specialId, 1, new KgApi.PlaylistSongsCallback() {
            public void onResult(List<KgApi.Song> songs, int total) {
                if (mActivity == null) return;
                if (songs.isEmpty()) {
                    MusicActivity.toast("歌单为空或加载失败");
                    return;
                }
                List<MusicSearchApi.Song> msSongs = convertToMs(songs);
                MusicActivity.playSongs(msSongs, 0);
                MusicActivity.toast("已导入 " + songs.size() + " 首歌曲");
            }
            public void onError(String msg) {
                MusicActivity.toast("导入失败: " + msg);
            }
        });
    }

    private void populatePlaylists(List<KgApi.Playlist> list) {
        mPlaylistLoading.setVisibility(View.GONE);
        if (list == null || list.isEmpty()) return;
        mPlaylistGrid.setVisibility(View.VISIBLE);
        mPlaylistGrid.removeAllViews();

        int count = Math.min(list.size(), 6);
        int gap = MusicActivity.dp(5);
        int cardH = MusicActivity.dp(50);

        for (int i = 0; i < count; i += 2) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rowLp.topMargin = gap;
            row.setLayoutParams(rowLp);

            for (int j = 0; j < 2; j++) {
                int index = i + j;
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, cardH, 1.0f);
                if (j == 0) cardLp.rightMargin = gap / 2;
                else cardLp.leftMargin = gap / 2;

                if (index < count)
                    row.addView(buildPlaylistCard(list.get(index)), cardLp);
                else
                    row.addView(new View(mActivity), cardLp);
            }
            mPlaylistGrid.addView(row);
        }
    }

    private View buildPlaylistCard(KgApi.Playlist item) {
        FrameLayout card = new FrameLayout(mActivity);
        card.setBackground(MusicActivity.rd(6, MusicActivity.CLR_CARD));

        LinearLayout inner = new LinearLayout(mActivity);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        int pad = MusicActivity.dp(6);
        inner.setPadding(pad, pad, pad, pad);
        inner.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        int coverSize = MusicActivity.dp(38);
        ImageView cover = new ImageView(mActivity);
        cover.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(3)); coverBg.setColor(0xFFE0E0E0);
        cover.setBackground(coverBg);
        MusicActivity.loadCover(cover, item.cover);
        inner.addView(cover);

        TextView title = new TextView(mActivity);
        title.setText(item.title != null ? item.title : "");
        title.setTextSize(11);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titleLp.leftMargin = MusicActivity.dp(6);
        title.setLayoutParams(titleLp);
        inner.addView(title);
        card.addView(inner);

        card.setOnClickListener(v -> playPlaylist(item));
        return card;
    }

    private void playPlaylist(KgApi.Playlist item) {
        KgApi.getTopListDetail(item.id, 1, new KgApi.PlaylistSongsCallback() {
            public void onResult(List<KgApi.Song> songs, int total) {
                if (mActivity == null || songs.isEmpty()) return;
                List<MusicSearchApi.Song> msSongs = convertToMs(songs);
                MusicActivity.playSongs(msSongs, 0);
                MusicActivity.toast("\u6B63\u5728\u64AD\u653E: " + item.title);
            }
            public void onError(String msg) {
                MusicActivity.toast("\u52A0\u8F7D\u6B4C\u5355\u5931\u8D25: " + msg);
            }
        });
    }

    private void populateArtists(List<KgApi.Playlist> list) {
        mArtistLoading.setVisibility(View.GONE);
        if (list == null || list.isEmpty()) return;
        mArtistGrid.setVisibility(View.VISIBLE);
        mArtistGrid.removeAllViews();

        Random rng = new Random();
        java.util.Collections.shuffle(list, rng);

        int count = Math.min(list.size(), 8);
        int avatarSize = MusicActivity.dp(46);
        int itemsPerRow = 4;
        int gap = MusicActivity.dp(4);

        for (int i = 0; i < count; i += itemsPerRow) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rowLp.topMargin = gap;
            row.setLayoutParams(rowLp);

            for (int j = 0; j < itemsPerRow; j++) {
                int index = i + j;
                if (index < count)
                    row.addView(buildArtistItem(list.get(index), avatarSize),
                        new LinearLayout.LayoutParams(0, -2, 1.0f));
                else
                    row.addView(new View(mActivity), new LinearLayout.LayoutParams(0, -2, 1.0f));
            }
            mArtistGrid.addView(row);
        }
    }

    private View buildArtistItem(KgApi.Playlist item, int size) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout avatarFrame = new FrameLayout(mActivity);
        avatarFrame.setLayoutParams(new LinearLayout.LayoutParams(size, size));

        ImageView avatar = new ImageView(mActivity);
        avatar.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        String artistTitle = item.title != null ? item.title : "";
        avatarBg.setColor(CARD_COLORS[Math.abs(artistTitle.hashCode()) % CARD_COLORS.length]);
        avatar.setBackground(avatarBg);
        MusicActivity.loadCover(avatar, item.cover);
        avatarFrame.addView(avatar);

        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(MusicActivity.dp(1), MusicActivity.CLR_ACCENT_LIGHT);
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringView = new FrameLayout(mActivity);
        ringView.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        ringView.setBackground(ring);
        avatarFrame.addView(ringView);
        card.addView(avatarFrame);

        TextView name = new TextView(mActivity);
        name.setText(item.title);
        name.setTextSize(10);
        name.setTextColor(MusicActivity.CLR_TEXT);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) name.getLayoutParams()).topMargin = MusicActivity.dp(3);
        card.addView(name);

        card.setOnClickListener(v -> playArtist(item));
        return card;
    }

    private void playArtist(KgApi.Playlist item) {
        KgApi.search(item.title, 1, "music", new KgApi.SongListCallback() {
            public void onResult(List<KgApi.Song> songs, int total) {
                if (mActivity == null || songs.isEmpty()) {
                    MusicActivity.toast("\u672A\u627E\u5230 " + item.title + " \u7684\u6B4C\u66F2");
                    return;
                }
                List<MusicSearchApi.Song> msSongs = convertToMs(songs);
                MusicActivity.playSongs(msSongs, 0);
                MusicActivity.toast("\u6B63\u5728\u64AD\u653E: " + item.title);
            }
        });
    }

    private List<MusicSearchApi.Song> convertToMs(List<KgApi.Song> kgSongs) {
        List<MusicSearchApi.Song> result = new ArrayList<>();
        for (KgApi.Song ks : kgSongs) {
            result.add(convertSingle(ks));
        }
        return result;
    }

    private void showSearchPage(String kw) {
        mSearchKeyword = kw;
        mSearchPageNum = 0;
        mSearchTotal = 0;
        mSearchLoading = false;

        if (mHomeScroll != null) mHomeScroll.setVisibility(View.GONE);

        if (mSearchPage != null) {
            ((FrameLayout) mSearchPage.getParent()).removeView(mSearchPage);
        }

        mSearchPage = new FrameLayout(mActivity);
        mSearchPage.setBackgroundColor(MusicActivity.CLR_BG);

        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(MusicActivity.dp(12), MusicActivity.sStatusBarH + MusicActivity.dp(8),
                MusicActivity.dp(12), MusicActivity.dp(8));

        LinearLayout topRow = new LinearLayout(mActivity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, 0, 0, MusicActivity.dp(8));

        TextView backBtn = new TextView(mActivity);
        backBtn.setText("\u2190");
        backBtn.setTextSize(20);
        backBtn.setTextColor(MusicActivity.CLR_ACCENT);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(MusicActivity.dp(2), 0, MusicActivity.dp(10), 0);
        backBtn.setOnClickListener(v -> hideSearchPage());
        topRow.addView(backBtn);

        TextView title = new TextView(mActivity);
        title.setText("\u641C\u7D22\uFF1A" + kw);
        title.setTextSize(15);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        topRow.addView(title);
        content.addView(topRow);

        mSearchScroll = new ScrollView(mActivity);
        mSearchPageList = new LinearLayout(mActivity);
        mSearchPageList.setOrientation(LinearLayout.VERTICAL);
        mSearchScroll.addView(mSearchPageList);
        content.addView(mSearchScroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        ProgressBar loading = new ProgressBar(mActivity);
        loading.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        loading.setPadding(0, MusicActivity.dp(8), 0, 0);
        mSearchPageList.addView(loading);

        mSearchLoadMore = new TextView(mActivity);
        mSearchLoadMore.setText("\u52A0\u8F7D\u66F4\u591A...");
        mSearchLoadMore.setTextSize(12);
        mSearchLoadMore.setTextColor(MusicActivity.CLR_ACCENT);
        mSearchLoadMore.setGravity(Gravity.CENTER);
        mSearchLoadMore.setPadding(0, MusicActivity.dp(8), 0, 0);
        mSearchLoadMore.setVisibility(View.GONE);
        mSearchLoadMore.setOnClickListener(v -> {
            if (!mSearchLoading) loadSearchMore();
        });
        mSearchPageList.addView(mSearchLoadMore);

        FrameLayout container = (FrameLayout) mHomeScroll.getParent();
        container.addView(mSearchPage);

        loadSearchMore();
    }

    private void hideSearchPage() {
        if (mHomeScroll != null) mHomeScroll.setVisibility(View.VISIBLE);
        if (mSearchPage != null) {
            FrameLayout container = (FrameLayout) mSearchPage.getParent();
            if (container != null) container.removeView(mSearchPage);
            mSearchPage = null;
        }
    }

    private void loadSearchMore() {
        if (mSearchLoading) return;
        mSearchLoading = true;
        final int page = mSearchPageNum + 1;

        KgApi.search(mSearchKeyword, page, "music", new KgApi.SongListCallback() {
            public void onResult(List<KgApi.Song> songs, int total) {
                mHandler.post(() -> {
                    mSearchLoading = false;
                    mSearchTotal = total;

                    if (mSearchPageList == null) return;

                    if (mSearchPageNum == 0 && mSearchPageList.getChildCount() > 0) {
                        mSearchPageList.removeAllViews();
                        mSearchPageList.addView(mSearchLoadMore);
                    }

                    if (songs == null || songs.isEmpty()) {
                        if (mSearchPageNum == 0) {
                            TextView empty = new TextView(mActivity);
                            empty.setText("\u672A\u627E\u5230\u7ED3\u679C");
                            empty.setTextSize(14);
                            empty.setTextColor(MusicActivity.CLR_TEXT2);
                            empty.setPadding(0, MusicActivity.dp(8), 0, 0);
                            mSearchPageList.addView(empty, 0);
                        }
                        return;
                    }

                    mSearchPageNum = page;

                    int insertIdx = mSearchPageList.getChildCount() - 1;

                    for (KgApi.Song ks : songs) {
                        MusicSearchApi.Song ms = convertSingle(ks);

                        LinearLayout row = new LinearLayout(mActivity);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4));

                        ImageView cov = new ImageView(mActivity);
                        cov.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(40), MusicActivity.dp(40)));
                        if (ks.cover != null && !ks.cover.isEmpty()) {
                            MusicActivity.loadCircularCover(cov, ks.cover);
                        } else {
                            loadCoverFallback(cov, ks.title);
                        }
                        row.addView(cov);

                        LinearLayout info = new LinearLayout(mActivity);
                        info.setOrientation(LinearLayout.VERTICAL);
                        info.setPadding(MusicActivity.dp(8), 0, MusicActivity.dp(4), 0);

                        TextView nameTv = new TextView(mActivity);
                        nameTv.setText(ks.title != null ? ks.title : "");
                        nameTv.setTextSize(13);
                        nameTv.setTextColor(MusicActivity.CLR_TEXT);
                        nameTv.setSingleLine(true);
                        nameTv.setEllipsize(TextUtils.TruncateAt.END);
                        info.addView(nameTv);

                        TextView artistTv = new TextView(mActivity);
                        artistTv.setText(ks.artist != null ? ks.artist : "");
                        artistTv.setTextSize(11);
                        artistTv.setTextColor(MusicActivity.CLR_TEXT2);
                        artistTv.setSingleLine(true);
                        info.addView(artistTv);

                        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
                        row.addView(info);

                        final MusicSearchApi.Song fms = ms;
                        row.setOnClickListener(v -> {
                            try {
                                MusicActivity.playSong(fms);
                            } catch (Throwable e) {
                                MusicActivity.toast("播放失败");
                            }
                        });
                        mSearchPageList.addView(row, insertIdx++);
                    }

                    boolean hasMore = (mSearchTotal == 0) || (mSearchPageList.getChildCount() - 1 < mSearchTotal);
                    mSearchLoadMore.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                    if (!hasMore) {
                        mSearchLoadMore.setText("已加载全部");
                    } else {
                        mSearchLoadMore.setText("加载更多...");
                    }
                });
            }
            public void onError(String msg) {
                mHandler.post(() -> {
                    mSearchLoading = false;
                    if (mSearchPageNum == 0) MusicActivity.toast("音源接口请求失败");
                });
            }
        });
    }

    private void loadCoverFallback(ImageView iv, String title) {
        String safeTitle = title != null && !title.isEmpty() ? title : "music";
        int color = CARD_COLORS[Math.abs(safeTitle.hashCode()) % CARD_COLORS.length];
        int size = MusicActivity.dp(36);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        iv.setImageBitmap(bmp);
    }

    private MusicSearchApi.Song convertSingle(KgApi.Song ks) {
        MusicSearchApi.Song ms = new MusicSearchApi.Song();
        if (ks == null) return ms;
        String hash = ks.hash != null ? ks.hash : "";
        String id = ks.id != null ? ks.id : hash;
        ms.id = hash.isEmpty() ? id : hash;
        ms.hash = hash;
        ms.hash320 = ks.hash320;
        ms.sqHash = ks.sqHash;
        ms.originHash = ks.originHash;
        ms.albumId = ks.albumId;
        ms.albumAudioId = ks.albumAudioId;
        ms.title = ks.title != null ? ks.title : "未知歌曲";
        ms.artist = ks.artist != null ? ks.artist : "未知歌手";
        ms.cover = ks.cover != null ? ks.cover : "";
        ms.duration = ks.duration;
        ms.platform = 0;
        return ms;
    }
}
