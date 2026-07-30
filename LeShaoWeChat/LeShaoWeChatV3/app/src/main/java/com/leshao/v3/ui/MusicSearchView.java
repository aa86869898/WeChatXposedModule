package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MusicSearchView {

    private static final int PAGE_SIZE = 30;
    private static final long DEBOUNCE_MS = 300;
    private static final int MAX_HISTORY = 15;

    private static final String[] TYPE_LABELS = {"单曲", "专辑", "歌单", "歌手", "歌词"};
    private static final String[] TYPE_KEYS = {"music", "album", "sheet", "artist", "lyric"};

    private Activity mActivity;
    private EditText mSearchInput;
    private LinearLayout mChipContainer;
    private LinearLayout mResultsContainer;
    private LinearLayout mHistoryContainer;
    private ProgressBar mProgress;
    private TextView mLoadMoreBtn;
    private ScrollView mScrollRoot;

    private int mCurrentTypeIndex = 0;
    private int mCurrentPage = 1;
    private int mTotalResults = 0;
    private String mCurrentKeyword = "";
    private boolean mIsLoading;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mDebounceRunnable;
    private final List<View> mChipViews = new ArrayList<>();
    private List<String> mSearchHistory = new ArrayList<>();

    public View createView(Activity activity) {
        mActivity = activity;
        loadHistory();

        mScrollRoot = new ScrollView(mActivity);
        mScrollRoot.setBackgroundColor(MusicActivity.CLR_BG);

        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(MusicActivity.dp(11), MusicActivity.sStatusBarH + MusicActivity.dp(6),
                MusicActivity.dp(11), MusicActivity.dp(8));

        buildSearchBar(content);
        buildTypeChips(content);
        buildHistorySection(content);
        buildResultsArea(content);
        buildLoadMore(content);

        mScrollRoot.addView(content);
        return mScrollRoot;
    }

    public void onViewReady() {}

    private void buildSearchBar(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(MusicActivity.rd(18, MusicActivity.CLR_CARD));
        int padH = MusicActivity.dp(10);
        int padV = MusicActivity.dp(6);
        bar.setPadding(padH, padV, padH, padV);
        parent.addView(bar);

        TextView icon = new TextView(mActivity);
        icon.setText("\uD83D\uDD0D");
        icon.setTextSize(13);
        icon.setPadding(0, 0, MusicActivity.dp(6), 0);
        bar.addView(icon);

        mSearchInput = new EditText(mActivity);
        mSearchInput.setHint("搜索歌曲/歌手/专辑");
        mSearchInput.setTextSize(13);
        mSearchInput.setTextColor(MusicActivity.CLR_TEXT);
        mSearchInput.setHintTextColor(MusicActivity.CLR_TEXT2);
        mSearchInput.setBackground(null);
        mSearchInput.setSingleLine(true);
        mSearchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mSearchInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        bar.addView(mSearchInput);

        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (mDebounceRunnable != null) mHandler.removeCallbacks(mDebounceRunnable);
                String kw = s.toString().trim();
                if (kw.isEmpty()) {
                    showHistory();
                    return;
                }
                mDebounceRunnable = () -> performSearch(kw, false);
                mHandler.postDelayed(mDebounceRunnable, DEBOUNCE_MS);
            }
        });

        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String kw = mSearchInput.getText().toString().trim();
                if (kw.length() > 0) {
                    addToHistory(kw);
                    performSearch(kw, false);
                }
                return true;
            }
            return false;
        });
    }

    private void buildTypeChips(LinearLayout parent) {
        HorizontalScrollView hsv = new HorizontalScrollView(mActivity);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(0, MusicActivity.dp(6), 0, MusicActivity.dp(4));
        mChipContainer = new LinearLayout(mActivity);
        mChipContainer.setOrientation(LinearLayout.HORIZONTAL);
        mChipContainer.setPadding(MusicActivity.dp(1), 0, MusicActivity.dp(1), 0);
        hsv.addView(mChipContainer);
        parent.addView(hsv);

        for (int i = 0; i < TYPE_LABELS.length; i++) {
            final int idx = i;
            TextView chip = new TextView(mActivity);
            chip.setText(TYPE_LABELS[i]);
            chip.setTextSize(11);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(MusicActivity.dp(10), MusicActivity.dp(4), MusicActivity.dp(10), MusicActivity.dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, MusicActivity.dp(5), 0);
            chip.setLayoutParams(lp);
            chip.setBackground(MusicActivity.rd(12, i == 0 ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_INPUT));
            chip.setTextColor(i == 0 ? 0xFFFFFFFF : MusicActivity.CLR_TEXT2);
            chip.setOnClickListener(v -> {
                mCurrentTypeIndex = idx;
                updateChipStyles();
                if (mCurrentKeyword.length() > 0) performSearch(mCurrentKeyword, false);
            });
            mChipContainer.addView(chip);
            mChipViews.add(chip);
        }
    }

    private void updateChipStyles() {
        for (int i = 0; i < mChipViews.size(); i++) {
            TextView chip = (TextView) mChipViews.get(i);
            boolean sel = (i == mCurrentTypeIndex);
            chip.setBackground(MusicActivity.rd(12, sel ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_INPUT));
            chip.setTextColor(sel ? 0xFFFFFFFF : MusicActivity.CLR_TEXT2);
        }
    }

    private void buildHistorySection(LinearLayout parent) {
        mHistoryContainer = new LinearLayout(mActivity);
        mHistoryContainer.setOrientation(LinearLayout.VERTICAL);
        mHistoryContainer.setPadding(0, MusicActivity.dp(4), 0, 0);
        mHistoryContainer.setVisibility(View.VISIBLE);
        parent.addView(mHistoryContainer);
        refreshHistoryView();
    }

    private void buildResultsArea(LinearLayout parent) {
        mProgress = new ProgressBar(mActivity);
        mProgress.setVisibility(View.GONE);
        mProgress.setPadding(0, MusicActivity.dp(16), 0, 0);
        parent.addView(mProgress);

        mResultsContainer = new LinearLayout(mActivity);
        mResultsContainer.setOrientation(LinearLayout.VERTICAL);
        mResultsContainer.setVisibility(View.GONE);
        parent.addView(mResultsContainer);
    }

    private void buildLoadMore(LinearLayout parent) {
        mLoadMoreBtn = new TextView(mActivity);
        mLoadMoreBtn.setText("加载更多");
        mLoadMoreBtn.setTextSize(12);
        mLoadMoreBtn.setTextColor(MusicActivity.CLR_ACCENT);
        mLoadMoreBtn.setTypeface(null, Typeface.BOLD);
        mLoadMoreBtn.setGravity(Gravity.CENTER);
        mLoadMoreBtn.setPadding(0, MusicActivity.dp(12), 0, MusicActivity.dp(12));
        mLoadMoreBtn.setVisibility(View.GONE);
        mLoadMoreBtn.setOnClickListener(v -> {
            if (!mIsLoading && mCurrentKeyword.length() > 0) {
                performSearch(mCurrentKeyword, true);
            }
        });
        parent.addView(mLoadMoreBtn);
    }

    private void showHistory() {
        mHistoryContainer.setVisibility(View.VISIBLE);
        mResultsContainer.removeAllViews();
        mResultsContainer.setVisibility(View.GONE);
        mLoadMoreBtn.setVisibility(View.GONE);
    }

    private void refreshHistoryView() {
        mHistoryContainer.removeAllViews();

        if (mSearchHistory.isEmpty()) return;

        LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, MusicActivity.dp(4));

        TextView title = new TextView(mActivity);
        title.setText("搜索历史");
        title.setTextSize(12);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        header.addView(title);

        TextView clearBtn = new TextView(mActivity);
        clearBtn.setText("清空");
        clearBtn.setTextSize(11);
        clearBtn.setTextColor(MusicActivity.CLR_TEXT2);
        clearBtn.setPadding(MusicActivity.dp(8), MusicActivity.dp(2), 0, MusicActivity.dp(2));
        clearBtn.setOnClickListener(v -> {
            mSearchHistory.clear();
            saveHistory();
            showHistory();
            refreshHistoryView();
        });
        header.addView(clearBtn);
        mHistoryContainer.addView(header);

        LinearLayout tagsRow = new LinearLayout(mActivity);
        tagsRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout row = null;
        int rowWidth = 0;
        for (String kw : mSearchHistory) {
            TextView tag = new TextView(mActivity);
            tag.setText(kw);
            tag.setTextSize(11);
            tag.setTextColor(MusicActivity.CLR_TEXT);
            tag.setBackground(MusicActivity.rd(12, MusicActivity.CLR_INPUT));
            tag.setPadding(MusicActivity.dp(8), MusicActivity.dp(3), MusicActivity.dp(8), MusicActivity.dp(3));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, MusicActivity.dp(5), MusicActivity.dp(5));
            tag.setLayoutParams(lp);
            tag.setOnClickListener(v -> {
                mSearchInput.setText(kw);
                addToHistory(kw);
                performSearch(kw, false);
            });
            tagsRow.addView(tag);
        }
        mHistoryContainer.addView(tagsRow);
    }

    private void performSearch(String keyword, boolean append) {
        if (keyword.isEmpty() || mIsLoading) return;

        if (!append) {
            mCurrentPage = 1;
            mTotalResults = 0;
            mCurrentKeyword = keyword;
            mResultsContainer.removeAllViews();
            mResultsContainer.setVisibility(View.VISIBLE);
            mHistoryContainer.setVisibility(View.GONE);
        }

        mIsLoading = true;
        mProgress.setVisibility(View.VISIBLE);
        mLoadMoreBtn.setVisibility(View.GONE);

        String type = TYPE_KEYS[mCurrentTypeIndex];
        KgApi.search(keyword, mCurrentPage, type, new KgApi.SongListCallback() {
            public void onResult(List<KgApi.Song> songs, int total) {
                if (mActivity == null) return;
                mIsLoading = false;
                mProgress.setVisibility(View.GONE);
                mTotalResults = total;

                if (!append) mResultsContainer.removeAllViews();

                if (songs == null || songs.isEmpty()) {
                    if (mResultsContainer.getChildCount() == 0) {
                        TextView empty = new TextView(mActivity);
                        empty.setText("没有搜到结果");
                        empty.setTextSize(12);
                        empty.setTextColor(MusicActivity.CLR_TEXT2);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, MusicActivity.dp(30), 0, 0);
                        mResultsContainer.addView(empty);
                    }
                } else {
                    for (KgApi.Song song : songs) {
                        View item = buildResultItem(song, type);
                        if (item != null) mResultsContainer.addView(item);
                    }
                    if (!append) mCurrentPage++;
                    else mCurrentPage++;
                }

                boolean hasMore = songs != null && songs.size() >= PAGE_SIZE;
                mLoadMoreBtn.setVisibility(hasMore ? View.VISIBLE : View.GONE);
            }
        });
    }

    private View buildResultItem(KgApi.Song song, String type) {
        LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(MusicActivity.rd(6, MusicActivity.CLR_CARD));
        item.setPadding(MusicActivity.dp(8), MusicActivity.dp(7), MusicActivity.dp(8), MusicActivity.dp(7));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, MusicActivity.dp(4));
        item.setLayoutParams(lp);

        ImageView cover = new ImageView(mActivity);
        int cs = MusicActivity.dp(40);
        cover.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(4));
        coverBg.setColor(MusicActivity.CLR_INPUT);
        cover.setBackground(coverBg);
        MusicActivity.loadCover(cover, song.cover);
        item.addView(cover);

        LinearLayout textCol = new LinearLayout(mActivity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        tlp.leftMargin = MusicActivity.dp(8);
        textCol.setLayoutParams(tlp);

        TextView titleTv = new TextView(mActivity);
        titleTv.setText(song.title);
        titleTv.setTextSize(13);
        titleTv.setTextColor(MusicActivity.CLR_TEXT);
        titleTv.setSingleLine(true);
        textCol.addView(titleTv);

        String subtitle = "";
        if ("music".equals(type)) subtitle = song.artist;
        else if ("artist".equals(type)) subtitle = song.artist;
        else if ("album".equals(type)) subtitle = song.album;
        else if ("sheet".equals(type)) subtitle = song.album;

        if (!subtitle.isEmpty()) {
            TextView subTv = new TextView(mActivity);
            subTv.setText(subtitle);
            subTv.setTextSize(11);
            subTv.setTextColor(MusicActivity.CLR_TEXT2);
            subTv.setSingleLine(true);
            textCol.addView(subTv);
        }

        item.addView(textCol);

        TextView playBtn = new TextView(mActivity);
        playBtn.setText("\u25B6");
        playBtn.setTextSize(14);
        playBtn.setTextColor(MusicActivity.CLR_ACCENT);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setPadding(MusicActivity.dp(8), 0, 0, 0);
        playBtn.setOnClickListener(v -> {
            MusicSearchApi.Song ms = new MusicSearchApi.Song();
            ms.id = song.hash.isEmpty() ? song.id : song.hash;
            ms.hash = song.hash;
            ms.title = song.title;
            ms.artist = song.artist;
            ms.cover = song.cover;
            ms.duration = song.duration;
            MusicActivity.playSong(ms);
        });
        item.addView(playBtn);

        return item;
    }

    private void addToHistory(String keyword) {
        if (keyword.isEmpty()) return;
        mSearchHistory.remove(keyword);
        mSearchHistory.add(0, keyword);
        if (mSearchHistory.size() > MAX_HISTORY) {
            mSearchHistory = mSearchHistory.subList(0, MAX_HISTORY);
        }
        saveHistory();
        refreshHistoryView();
    }

    @SuppressWarnings("unchecked")
    private void loadHistory() {
        SharedPreferences prefs = mActivity.getSharedPreferences("music_search", Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("history", new LinkedHashSet<>());
        mSearchHistory = new ArrayList<>(saved);
    }

    private void saveHistory() {
        SharedPreferences prefs = mActivity.getSharedPreferences("music_search", Context.MODE_PRIVATE);
        prefs.edit().putStringSet("history", new LinkedHashSet<>(mSearchHistory)).apply();
    }
}
