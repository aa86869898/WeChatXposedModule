package com.leshao.v3.ui;

import android.app.Activity;
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
import java.util.List;
import java.util.Locale;

public class MusicSearchView {

    private static final int PAGE_SIZE = 30;
    private static final long DEBOUNCE_MS = 300;

    private static final String[] TYPE_LABELS = {"单曲", "专辑", "歌单", "歌手", "歌词"};
    private static final String[] TYPE_KEYS   = {"music", "album", "sheet", "artist", "lyric"};

    private Activity mActivity;

    private EditText mSearchInput;
    private LinearLayout mChipContainer;
    private LinearLayout mResultsContainer;
    private LinearLayout mEmptyState;
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

    public View createView(Activity activity) {
        mActivity = activity;

        mScrollRoot = new ScrollView(mActivity);
        mScrollRoot.setBackgroundColor(MusicActivity.CLR_BG);

        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(MusicActivity.dp(11), MusicActivity.dp(10), MusicActivity.dp(11), MusicActivity.dp(56));

        buildSearchBar(content);
        buildTypeChips(content);
        buildEmptyState(content);
        buildResultsArea(content);
        buildLoadMore(content);

        mScrollRoot.addView(content);
        return mScrollRoot;
    }

    public void onViewReady() {
    }

    private void buildSearchBar(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(MusicActivity.rd(18, MusicActivity.CLR_CARD));
        int padH = MusicActivity.dp(11);
        int padV = MusicActivity.dp(7);
        bar.setPadding(padH, padV, padH, padV);
        parent.addView(bar);

        TextView icon = new TextView(mActivity);
        icon.setText("\uD83D\uDD0D");
        icon.setTextSize(13);
        icon.setPadding(0, 0, MusicActivity.dp(6), 0);
        bar.addView(icon);

        mSearchInput = new EditText(mActivity);
        mSearchInput.setHint("搜索歌曲/歌手/专辑");
        mSearchInput.setTextSize(14);
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
            @Override
            public void afterTextChanged(Editable s) {
                if (mDebounceRunnable != null) mHandler.removeCallbacks(mDebounceRunnable);
                mDebounceRunnable = () -> {
                    String kw = s.toString().trim();
                    if (kw.length() > 0) performSearch(kw, false);
                    else resetResults();
                };
                mHandler.postDelayed(mDebounceRunnable, DEBOUNCE_MS);
            }
        });

        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String kw = mSearchInput.getText().toString().trim();
                if (kw.length() > 0) performSearch(kw, false);
                return true;
            }
            return false;
        });
    }

    private void buildTypeChips(LinearLayout parent) {
        LinearLayout chipWrapper = new LinearLayout(mActivity);
        chipWrapper.setPadding(0, MusicActivity.dp(8), 0, MusicActivity.dp(6));
        parent.addView(chipWrapper);

        HorizontalScrollView hsv = new HorizontalScrollView(mActivity);
        hsv.setHorizontalScrollBarEnabled(false);
        mChipContainer = new LinearLayout(mActivity);
        mChipContainer.setOrientation(LinearLayout.HORIZONTAL);
        mChipContainer.setPadding(MusicActivity.dp(1), 0, MusicActivity.dp(1), 0);
        hsv.addView(mChipContainer);
        chipWrapper.addView(hsv);

        for (int i = 0; i < TYPE_LABELS.length; i++) {
            final int idx = i;
            TextView chip = new TextView(mActivity);
            chip.setText(TYPE_LABELS[i]);
            chip.setTextSize(12);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            int padH = MusicActivity.dp(12);
            int padV = MusicActivity.dp(5);
            chip.setPadding(padH, padV, padH, padV);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, MusicActivity.dp(6), 0);
            chip.setLayoutParams(lp);

            chip.setBackground(MusicActivity.rd(14, i == 0 ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_INPUT));
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
            chip.setBackground(MusicActivity.rd(14, sel ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_INPUT));
            chip.setTextColor(sel ? 0xFFFFFFFF : MusicActivity.CLR_TEXT2);
        }
    }

    private void buildEmptyState(LinearLayout parent) {
        mEmptyState = new LinearLayout(mActivity);
        mEmptyState.setOrientation(LinearLayout.VERTICAL);
        mEmptyState.setGravity(Gravity.CENTER);
        mEmptyState.setPadding(0, MusicActivity.dp(56), 0, 0);
        mEmptyState.setVisibility(View.VISIBLE);

        TextView emoji = new TextView(mActivity);
        emoji.setText("\uD83C\uDFB5");
        emoji.setTextSize(34);
        emoji.setGravity(Gravity.CENTER);
        mEmptyState.addView(emoji);

        TextView hint = new TextView(mActivity);
        hint.setText("搜索你喜欢的音乐");
        hint.setTextSize(11);
        hint.setTextColor(MusicActivity.CLR_TEXT2);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, MusicActivity.dp(8), 0, 0);
        mEmptyState.addView(hint);

        parent.addView(mEmptyState);
    }

    private void buildResultsArea(LinearLayout parent) {
        mResultsContainer = new LinearLayout(mActivity);
        mResultsContainer.setOrientation(LinearLayout.VERTICAL);
        mResultsContainer.setVisibility(View.GONE);

        mProgress = new ProgressBar(mActivity);
        mProgress.setVisibility(View.GONE);
        mProgress.setPadding(0, MusicActivity.dp(22), 0, 0);

        parent.addView(mProgress);
        parent.addView(mResultsContainer);
    }

    private void buildLoadMore(LinearLayout parent) {
        mLoadMoreBtn = new TextView(mActivity);
        mLoadMoreBtn.setText("加载更多");
        mLoadMoreBtn.setTextSize(13);
        mLoadMoreBtn.setTextColor(MusicActivity.CLR_ACCENT);
        mLoadMoreBtn.setTypeface(null, Typeface.BOLD);
        mLoadMoreBtn.setGravity(Gravity.CENTER);
        mLoadMoreBtn.setPadding(0, MusicActivity.dp(14), 0, MusicActivity.dp(14));
        mLoadMoreBtn.setVisibility(View.GONE);
        mLoadMoreBtn.setOnClickListener(v -> {
            if (!mIsLoading && mCurrentKeyword.length() > 0) {
                performSearch(mCurrentKeyword, true);
            }
        });
        parent.addView(mLoadMoreBtn);
    }

    private void performSearch(String keyword, boolean append) {
        if (keyword.isEmpty()) return;
        if (mIsLoading) return;

        if (!append) {
            mCurrentPage = 1;
            mTotalResults = 0;
            mCurrentKeyword = keyword;
            mResultsContainer.removeAllViews();
        }

        mIsLoading = true;
        mEmptyState.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);

        String type = TYPE_KEYS[mCurrentTypeIndex];
        KgApi.search(keyword, mCurrentPage, type, new KgApi.SongListCallback() {
            @Override
            public void onResult(List<KgApi.Song> songs, int total) {
                if (mActivity == null) return;
                mIsLoading = false;
                mProgress.setVisibility(View.GONE);

                if (mCurrentPage == 1) {
                    mResultsContainer.removeAllViews();
                }

                mTotalResults = total;

                if (append) {
                    mCurrentPage++;
                } else {
                    mResultsContainer.removeAllViews();
                }

                if (songs == null || songs.isEmpty()) {
                    if (mCurrentPage > 1) {
                        --mCurrentPage;
                    }
                    if (mResultsContainer.getChildCount() == 0) {
                        mEmptyState.setVisibility(View.VISIBLE);
                    }
                } else {
                    mEmptyState.setVisibility(View.GONE);
                    mResultsContainer.setVisibility(View.VISIBLE);

                    boolean firstPage = (mCurrentPage == 1);
                    if (firstPage && !append) {
                        mResultsContainer.removeAllViews();
                    }

                    for (KgApi.Song song : songs) {
                        View item = buildResultItem(song, type);
                        if (item != null) mResultsContainer.addView(item);
                    }

                    if (!append) mCurrentPage++;
                }

                updateLoadMoreVisibility();
            }
        });
    }

    private View buildResultItem(KgApi.Song song, String type) {
        switch (type) {
            case "music": return buildSongItem(song);
            case "album": return buildAlbumItem(song);
            case "sheet": return buildPlaylistItem(song);
            case "artist": return buildArtistItem(song);
            case "lyric": return buildLyricItem(song);
            default: return buildSongItem(song);
        }
    }

    private View buildSongItem(KgApi.Song song) {
        LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
        item.setPadding(MusicActivity.dp(8), MusicActivity.dp(7), MusicActivity.dp(8), MusicActivity.dp(7));

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
        itemLp.setMargins(0, 0, 0, MusicActivity.dp(6));
        item.setLayoutParams(itemLp);

        int coverSize = MusicActivity.dp(40);
        ImageView cover = new ImageView(mActivity);
        cover.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(MusicActivity.rd(6, 0xFFDDDDDD));
        MusicActivity.loadCover(cover, song.cover);
        item.addView(cover);

        LinearLayout info = new LinearLayout(mActivity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(MusicActivity.dp(8), 0, MusicActivity.dp(6), 0);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView title = new TextView(mActivity);
        title.setText(song.title);
        title.setTextSize(11);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setSingleLine(true);
        info.addView(title);

        TextView artist = new TextView(mActivity);
        artist.setText(song.artist);
        artist.setTextSize(9);
        artist.setTextColor(MusicActivity.CLR_TEXT2);
        artist.setSingleLine(true);
        artist.setPadding(0, MusicActivity.dp(2), 0, 0);
        info.addView(artist);

        item.addView(info);

        if (song.duration > 0) {
            TextView dur = new TextView(mActivity);
            dur.setText(formatDuration(song.duration));
            dur.setTextSize(9);
            dur.setTextColor(MusicActivity.CLR_TEXT2);
            dur.setPadding(MusicActivity.dp(3), 0, MusicActivity.dp(6), 0);
            item.addView(dur);
        }

        TextView playBtn = new TextView(mActivity);
        playBtn.setText("\u25B6");
        playBtn.setTextSize(13);
        playBtn.setTextColor(MusicActivity.CLR_ACCENT);
        playBtn.setPadding(MusicActivity.dp(6), MusicActivity.dp(3), MusicActivity.dp(3), MusicActivity.dp(3));
        item.addView(playBtn);

        item.setOnClickListener(v -> {
            MusicSearchApi.Song ms = new MusicSearchApi.Song();
            ms.id = song.hash != null ? song.hash : song.id;
            ms.hash = song.hash;
            ms.title = song.title;
            ms.artist = song.artist;
            ms.album = song.album;
            ms.cover = song.cover;
            ms.duration = song.duration;
            ms.platform = 0;
            MusicActivity.playSong(ms);
        });

        return item;
    }

    private View buildAlbumItem(KgApi.Song song) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
        int pad = MusicActivity.dp(8);
        card.setPadding(pad, pad, pad, pad);

        int cardWidth = (int) ((mActivity.getResources().getDisplayMetrics().widthPixels - MusicActivity.dp(11) * 2 - MusicActivity.dp(6) * 2) / 2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, -2);
        lp.setMargins(0, 0, MusicActivity.dp(6), MusicActivity.dp(6));
        card.setLayoutParams(lp);

        int imgSize = cardWidth - pad * 2;
        ImageView cover = new ImageView(mActivity);
        cover.setLayoutParams(new LinearLayout.LayoutParams(imgSize, imgSize));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(MusicActivity.rd(6, 0xFFDDDDDD));
        MusicActivity.loadCover(cover, song.cover);
        card.addView(cover);

        TextView title = new TextView(mActivity);
        title.setText(song.title);
        title.setTextSize(10);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setSingleLine(true);
        title.setMaxLines(1);
        title.setPadding(0, MusicActivity.dp(6), 0, MusicActivity.dp(1));
        title.setLayoutParams(new LinearLayout.LayoutParams(cardWidth - pad * 2, -2));
        card.addView(title);

        TextView artist = new TextView(mActivity);
        artist.setText(song.artist);
        artist.setTextSize(9);
        artist.setTextColor(MusicActivity.CLR_TEXT2);
        artist.setSingleLine(true);
        artist.setLayoutParams(new LinearLayout.LayoutParams(cardWidth - pad * 2, -2));
        card.addView(artist);

        card.setOnClickListener(v -> MusicActivity.toast("功能开发中"));
        return card;
    }

    private View buildPlaylistItem(KgApi.Song song) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
        int pad = MusicActivity.dp(8);
        card.setPadding(pad, pad, pad, pad);

        int cardWidth = (int) ((mActivity.getResources().getDisplayMetrics().widthPixels - MusicActivity.dp(11) * 2 - MusicActivity.dp(6) * 2) / 2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, -2);
        lp.setMargins(0, 0, MusicActivity.dp(6), MusicActivity.dp(6));
        card.setLayoutParams(lp);

        int imgSize = cardWidth - pad * 2;
        ImageView cover = new ImageView(mActivity);
        cover.setLayoutParams(new LinearLayout.LayoutParams(imgSize, imgSize));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(MusicActivity.rd(6, 0xFFDDDDDD));
        MusicActivity.loadCover(cover, song.cover);
        card.addView(cover);

        TextView title = new TextView(mActivity);
        title.setText(song.title);
        title.setTextSize(10);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setMaxLines(2);
        title.setPadding(0, MusicActivity.dp(6), 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(cardWidth - pad * 2, -2));
        card.addView(title);

        card.setOnClickListener(v -> MusicActivity.toast("功能开发中"));
        return card;
    }

    private View buildArtistItem(KgApi.Song song) {
        LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
        item.setPadding(MusicActivity.dp(8), MusicActivity.dp(7), MusicActivity.dp(8), MusicActivity.dp(7));

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
        itemLp.setMargins(0, 0, 0, MusicActivity.dp(6));
        item.setLayoutParams(itemLp);

        int avatarSize = MusicActivity.dp(38);
        ImageView avatar = new ImageView(mActivity);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(avatarSize, avatarSize));
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);

        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(0xFFDDDDDD);
        avatar.setBackground(circleBg);
        avatar.setClipToOutline(true);
        MusicActivity.loadCover(avatar, song.cover);
        item.addView(avatar);

        LinearLayout info = new LinearLayout(mActivity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(MusicActivity.dp(10), 0, 0, 0);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView name = new TextView(mActivity);
        name.setText(song.title);
        name.setTextSize(11);
        name.setTextColor(MusicActivity.CLR_TEXT);
        name.setSingleLine(true);
        info.addView(name);

        TextView songsCount = new TextView(mActivity);
        songsCount.setText(song.artist);
        songsCount.setTextSize(9);
        songsCount.setTextColor(MusicActivity.CLR_TEXT2);
        songsCount.setSingleLine(true);
        songsCount.setPadding(0, MusicActivity.dp(2), 0, 0);
        info.addView(songsCount);

        item.addView(info);

        TextView arrow = new TextView(mActivity);
        arrow.setText("\u203A");
        arrow.setTextSize(17);
        arrow.setTextColor(MusicActivity.CLR_TEXT2);
        arrow.setPadding(MusicActivity.dp(6), 0, 0, 0);
        item.addView(arrow);

        item.setOnClickListener(v -> MusicActivity.toast("功能开发中"));
        return item;
    }

    private View buildLyricItem(KgApi.Song song) {
        LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
        int pad = MusicActivity.dp(10);
        item.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, -2);
        itemLp.setMargins(0, 0, 0, MusicActivity.dp(6));
        item.setLayoutParams(itemLp);

        TextView title = new TextView(mActivity);
        title.setText(song.title);
        title.setTextSize(11);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        item.addView(title);

        if (song.artist != null && !song.artist.isEmpty()) {
            TextView artist = new TextView(mActivity);
            artist.setText(song.artist);
            artist.setTextSize(9);
            artist.setTextColor(MusicActivity.CLR_TEXT2);
            artist.setSingleLine(true);
            artist.setPadding(0, MusicActivity.dp(1), 0, MusicActivity.dp(4));
            item.addView(artist);
        }

        View div = new View(mActivity);
        div.setBackgroundColor(MusicActivity.CLR_DIV);
        div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        item.addView(div);

        TextView lyricHint = new TextView(mActivity);
        lyricHint.setText("匹配歌词: " + song.title);
        lyricHint.setTextSize(9);
        lyricHint.setTextColor(MusicActivity.CLR_TEXT2);
        lyricHint.setMaxLines(2);
        lyricHint.setPadding(0, MusicActivity.dp(6), 0, 0);
        item.addView(lyricHint);

        item.setOnClickListener(v -> MusicActivity.toast("功能开发中"));
        return item;
    }

    private void updateLoadMoreVisibility() {
        boolean hasMore = mTotalResults > 0
            && mCurrentPage * PAGE_SIZE < mTotalResults
            && mResultsContainer.getChildCount() > 0;
        mLoadMoreBtn.setVisibility(hasMore ? View.VISIBLE : View.GONE);
    }

    private void resetResults() {
        mCurrentPage = 1;
        mTotalResults = 0;
        mCurrentKeyword = "";
        mResultsContainer.removeAllViews();
        mResultsContainer.setVisibility(View.GONE);
        mProgress.setVisibility(View.GONE);
        mLoadMoreBtn.setVisibility(View.GONE);
        mEmptyState.setVisibility(View.VISIBLE);
    }

    private String formatDuration(int seconds) {
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", min, sec);
    }
}
