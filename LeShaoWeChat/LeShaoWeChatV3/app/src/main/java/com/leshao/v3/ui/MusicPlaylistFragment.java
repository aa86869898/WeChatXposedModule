package com.leshao.v3.ui;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

public class MusicPlaylistFragment extends Fragment {

    private TextView mTopBar;
    private HorizontalScrollView mChipsContainer;
    private LinearLayout mChipsRow;
    private LinearLayout mGridContainer;
    private TextView mLoadMoreBtn;
    private LinearLayout mContentContainer;

    private List<KgApi.Playlist> mCategories = new ArrayList<>();
    private List<KgApi.Playlist> mPlaylists = new ArrayList<>();
    private int mSelectedCatIdx = 0;
    private int mPage = 1;
    private boolean mLoading;
    private boolean mHasMore = true;

    private LinearLayout mDetailView;
    private LinearLayout mDetailSongList;
    private TextView mDetailLoadMoreBtn;
    private List<MusicSearchApi.Song> mDetailSongs = new ArrayList<>();
    private int mDetailPage = 1;
    private boolean mDetailLoading;
    private boolean mDetailHasMore = true;
    private String mDetailSpecialId;
    private KgApi.Playlist mCurrentDetailPl;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MusicActivity.CLR_BG);
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

        mTopBar = new TextView(getContext());
        mTopBar.setText("歌单");
        mTopBar.setTextSize(18);
        mTopBar.setTextColor(MusicActivity.CLR_TEXT);
        mTopBar.setTypeface(null, Typeface.BOLD);
        mTopBar.setPadding(MusicActivity.dp(16), MusicActivity.dp(16), MusicActivity.dp(16), MusicActivity.dp(12));
        mTopBar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        root.addView(mTopBar);

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.setFillViewport(true);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));

        mContentContainer = new LinearLayout(getContext());
        mContentContainer.setOrientation(LinearLayout.VERTICAL);

        buildChipsRow();
        buildGrid();
        buildLoadMore();
        buildDetailView();

        scrollView.addView(mContentContainer);
        root.addView(scrollView);

        loadCategories();

        return root;
    }

    // ===== Category Chips =====

    private void buildChipsRow() {
        mChipsContainer = new HorizontalScrollView(getContext());
        mChipsContainer.setHorizontalScrollBarEnabled(false);
        mChipsContainer.setPadding(MusicActivity.dp(12), 0, MusicActivity.dp(12), MusicActivity.dp(4));
        mChipsContainer.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        mChipsRow = new LinearLayout(getContext());
        mChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        mChipsRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        mChipsContainer.addView(mChipsRow);
        mContentContainer.addView(mChipsContainer);
    }

    private View buildChip(String label, final int idx) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(MusicActivity.dp(14), MusicActivity.dp(6), MusicActivity.dp(14), MusicActivity.dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(MusicActivity.dp(20));

        boolean sel = (idx == mSelectedCatIdx);
        bg.setColor(sel ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_INPUT);
        tv.setBackground(bg);
        tv.setTextColor(sel ? 0xFFFFFFFF : MusicActivity.CLR_TEXT2);
        tv.setTag(idx);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, MusicActivity.dp(4), MusicActivity.dp(8), MusicActivity.dp(4));
        tv.setLayoutParams(lp);

        tv.setOnClickListener(v -> {
            int ni = (int) tv.getTag();
            if (ni == mSelectedCatIdx) return;
            mSelectedCatIdx = ni;
            updateChipStyles();
            mPage = 1;
            mPlaylists.clear();
            mGridContainer.removeAllViews();
            mHasMore = true;
            mLoadMoreBtn.setVisibility(View.GONE);
            loadPlaylists();
        });
        return tv;
    }

    private void updateChipStyles() {
        for (int i = 0; i < mChipsRow.getChildCount(); i++) {
            View child = mChipsRow.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView tv = (TextView) child;
            int idx = (int) tv.getTag();
            boolean sel = (idx == mSelectedCatIdx);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(MusicActivity.dp(20));
            bg.setColor(sel ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_INPUT);
            tv.setBackground(bg);
            tv.setTextColor(sel ? 0xFFFFFFFF : MusicActivity.CLR_TEXT2);
        }
    }

    // ===== Playlist Grid =====

    private void buildGrid() {
        mGridContainer = new LinearLayout(getContext());
        mGridContainer.setOrientation(LinearLayout.VERTICAL);
        mGridContainer.setPadding(MusicActivity.dp(12), MusicActivity.dp(4), MusicActivity.dp(12), MusicActivity.dp(4));
        mGridContainer.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        mContentContainer.addView(mGridContainer);
    }

    private void buildLoadMore() {
        mLoadMoreBtn = new TextView(getContext());
        mLoadMoreBtn.setText("加载更多");
        mLoadMoreBtn.setTextSize(13);
        mLoadMoreBtn.setTextColor(MusicActivity.CLR_ACCENT);
        mLoadMoreBtn.setTypeface(null, Typeface.BOLD);
        mLoadMoreBtn.setGravity(Gravity.CENTER);
        mLoadMoreBtn.setPadding(MusicActivity.dp(12), MusicActivity.dp(10), MusicActivity.dp(12), MusicActivity.dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(MusicActivity.dp(6));
        bg.setColor(MusicActivity.CLR_INPUT);
        mLoadMoreBtn.setBackground(bg);
        mLoadMoreBtn.setVisibility(View.GONE);
        mLoadMoreBtn.setOnClickListener(v -> {
            if (!mLoading && mHasMore) {
                mPage++;
                loadPlaylists();
            }
        });
        mContentContainer.addView(mLoadMoreBtn);
    }

    private void addPlaylistCards(List<KgApi.Playlist> playlists) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int itemW = (screenW - MusicActivity.dp(40)) / 2;
        int itemH = MusicActivity.dp(140);

        int count = mGridContainer.getChildCount();
        LinearLayout currentRow = null;
        if (count > 0) {
            View last = mGridContainer.getChildAt(count - 1);
            if (last instanceof LinearLayout && ((LinearLayout) last).getChildCount() < 2) {
                currentRow = (LinearLayout) last;
            }
        }
        if (currentRow == null) {
            currentRow = new LinearLayout(getContext());
            currentRow.setOrientation(LinearLayout.HORIZONTAL);
            mGridContainer.addView(currentRow);
        }

        for (KgApi.Playlist pl : playlists) {
            if (currentRow.getChildCount() >= 2) {
                currentRow = new LinearLayout(getContext());
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                mGridContainer.addView(currentRow);
            }
            currentRow.addView(buildPlaylistCard(pl, itemW, itemH));
        }
    }

    private View buildPlaylistCard(final KgApi.Playlist pl, int itemW, int itemH) {
        FrameLayout card = new FrameLayout(getContext());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(itemW, itemH);
        clp.setMargins(MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4));
        card.setLayoutParams(clp);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(MusicActivity.dp(10));
        cardBg.setColor(MusicActivity.CLR_CARD);
        card.setBackground(cardBg);

        ImageView cover = new ImageView(getContext());
        FrameLayout.LayoutParams coverLp = new FrameLayout.LayoutParams(itemW, MusicActivity.dp(72));
        cover.setLayoutParams(coverLp);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(MusicActivity.rd(new float[]{
            MusicActivity.dp(10), MusicActivity.dp(10),
            MusicActivity.dp(10), MusicActivity.dp(10),
            0, 0, 0, 0
        }, 0xFFDDDDDD));
        MusicActivity.loadCover(cover, pl.cover);
        card.addView(cover);

        FrameLayout overlay = new FrameLayout(getContext());
        overlay.setLayoutParams(coverLp);
        int[] gradColors = {0x00000000, 0x99000000};
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, gradColors);
        overlay.setBackground(grad);
        card.addView(overlay);

        TextView title = new TextView(getContext());
        title.setText(pl.title);
        title.setTextSize(14);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setPadding(MusicActivity.dp(8), 0, MusicActivity.dp(8), 0);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(-2, -2);
        titleLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        titleLp.setMargins(0, 0, 0, MusicActivity.dp(8));
        title.setLayoutParams(titleLp);
        card.addView(title);

        TextView countTv = new TextView(getContext());
        countTv.setText(pl.songCount + "首");
        countTv.setTextSize(12);
        countTv.setTextColor(MusicActivity.CLR_TEXT2);
        countTv.setPadding(MusicActivity.dp(8), MusicActivity.dp(4), MusicActivity.dp(8), 0);
        FrameLayout.LayoutParams countLp = new FrameLayout.LayoutParams(-2, -2);
        countLp.topMargin = MusicActivity.dp(76);
        countTv.setLayoutParams(countLp);
        card.addView(countTv);

        card.setOnClickListener(v -> showDetail(pl));
        return card;
    }

    // ===== Detail View =====

    private void buildDetailView() {
        mDetailView = new LinearLayout(getContext());
        mDetailView.setOrientation(LinearLayout.VERTICAL);
        mDetailView.setVisibility(View.GONE);
        mContentContainer.addView(mDetailView);

        mDetailSongList = new LinearLayout(getContext());
        mDetailSongList.setOrientation(LinearLayout.VERTICAL);
        mDetailSongList.setPadding(MusicActivity.dp(8), 0, MusicActivity.dp(8), 0);

        mDetailLoadMoreBtn = new TextView(getContext());
        mDetailLoadMoreBtn.setText("加载更多");
        mDetailLoadMoreBtn.setTextSize(13);
        mDetailLoadMoreBtn.setTextColor(MusicActivity.CLR_ACCENT);
        mDetailLoadMoreBtn.setTypeface(null, Typeface.BOLD);
        mDetailLoadMoreBtn.setGravity(Gravity.CENTER);
        mDetailLoadMoreBtn.setPadding(MusicActivity.dp(12), MusicActivity.dp(10), MusicActivity.dp(12), MusicActivity.dp(10));
        GradientDrawable dlmBg = new GradientDrawable();
        dlmBg.setCornerRadius(MusicActivity.dp(6));
        dlmBg.setColor(MusicActivity.CLR_INPUT);
        mDetailLoadMoreBtn.setBackground(dlmBg);
        mDetailLoadMoreBtn.setVisibility(View.GONE);
        mDetailLoadMoreBtn.setOnClickListener(v -> {
            if (!mDetailLoading && mDetailHasMore) {
                mDetailPage++;
                loadDetailSongs();
            }
        });
    }

    private void showDetail(KgApi.Playlist pl) {
        mCurrentDetailPl = pl;
        mDetailSpecialId = pl.id;
        mDetailSongs.clear();
        mDetailPage = 1;
        mDetailLoading = false;
        mDetailHasMore = true;

        // Switch top bar
        mTopBar.setText("← 歌单详情");
        mTopBar.setOnClickListener(v -> goBackToGrid());

        // Hide grid, show detail
        mChipsContainer.setVisibility(View.GONE);
        mGridContainer.setVisibility(View.GONE);
        mLoadMoreBtn.setVisibility(View.GONE);

        mDetailView.removeAllViews();
        mDetailView.setVisibility(View.VISIBLE);

        // Header: cover + info
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(MusicActivity.dp(16), MusicActivity.dp(8), MusicActivity.dp(16), MusicActivity.dp(12));
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView cover = new ImageView(getContext());
        int coverSize = MusicActivity.dp(80);
        cover.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(MusicActivity.rd(8, 0xFFDDDDDD));
        MusicActivity.loadCover(cover, pl.cover);
        header.addView(cover);

        LinearLayout infoCol = new LinearLayout(getContext());
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(MusicActivity.dp(12), 0, 0, 0);

        TextView titleTv = new TextView(getContext());
        titleTv.setText(pl.title);
        titleTv.setTextSize(16);
        titleTv.setTextColor(MusicActivity.CLR_TEXT);
        titleTv.setTypeface(null, Typeface.BOLD);
        infoCol.addView(titleTv);

        if (pl.description != null && !pl.description.isEmpty()) {
            TextView descTv = new TextView(getContext());
            descTv.setText(pl.description);
            descTv.setTextSize(12);
            descTv.setTextColor(MusicActivity.CLR_TEXT2);
            descTv.setMaxLines(2);
            descTv.setPadding(0, MusicActivity.dp(4), 0, 0);
            infoCol.addView(descTv);
        }

        TextView countTv = new TextView(getContext());
        countTv.setText(pl.songCount + "首");
        countTv.setTextSize(12);
        countTv.setTextColor(MusicActivity.CLR_TEXT2);
        countTv.setPadding(0, MusicActivity.dp(4), 0, 0);
        infoCol.addView(countTv);

        header.addView(infoCol);
        mDetailView.addView(header);

        // Play all button
        TextView playAllBtn = new TextView(getContext());
        playAllBtn.setText("播放全部");
        playAllBtn.setTextSize(14);
        playAllBtn.setTextColor(0xFFFFFFFF);
        playAllBtn.setTypeface(null, Typeface.BOLD);
        playAllBtn.setGravity(Gravity.CENTER);
        playAllBtn.setPadding(MusicActivity.dp(24), MusicActivity.dp(8), MusicActivity.dp(24), MusicActivity.dp(8));
        GradientDrawable paBg = new GradientDrawable();
        paBg.setCornerRadius(MusicActivity.dp(20));
        paBg.setColor(MusicActivity.CLR_ACCENT);
        playAllBtn.setBackground(paBg);
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(-2, -2);
        paLp.gravity = Gravity.CENTER_HORIZONTAL;
        paLp.setMargins(0, 0, 0, MusicActivity.dp(8));
        playAllBtn.setLayoutParams(paLp);
        playAllBtn.setOnClickListener(v -> {
            if (!mDetailSongs.isEmpty()) {
                MusicActivity.playSongs(new ArrayList<>(mDetailSongs), 0);
            }
        });
        mDetailView.addView(playAllBtn);

        // Song list
        mDetailSongList.removeAllViews();
        mDetailView.addView(mDetailSongList);

        // Load more for detail
        mDetailLoadMoreBtn.setVisibility(View.GONE);
        mDetailView.addView(mDetailLoadMoreBtn);

        loadDetailSongs();
    }

    private void goBackToGrid() {
        mDetailView.setVisibility(View.GONE);
        mDetailView.removeAllViews();
        mDetailSongs.clear();

        mTopBar.setText("歌单");
        mTopBar.setOnClickListener(null);

        mChipsContainer.setVisibility(View.VISIBLE);
        mGridContainer.setVisibility(View.VISIBLE);
        if (mHasMore) {
            mLoadMoreBtn.setVisibility(View.VISIBLE);
        }
    }

    private void loadDetailSongs() {
        if (mDetailLoading || mDetailSpecialId == null) return;
        mDetailLoading = true;

        if (mDetailPage == 1) {
            mDetailLoadMoreBtn.setVisibility(View.GONE);
        } else {
            mDetailLoadMoreBtn.setText("加载中...");
        }

        KgApi.getPlaylistDetail(mDetailSpecialId, mDetailPage, new KgApi.PlaylistSongsCallback() {
            @Override
            public void onResult(List<KgApi.Song> kgSongs, int total) {
                mDetailLoading = false;
                List<MusicSearchApi.Song> songs = convertSongs(kgSongs);
                mDetailSongs.addAll(songs);
                mDetailHasMore = mDetailSongs.size() < total;
                MusicActivity.MAIN.post(() -> {
                    addDetailSongRows(songs);
                    if (mDetailHasMore) {
                        mDetailLoadMoreBtn.setText("加载更多");
                        mDetailLoadMoreBtn.setVisibility(View.VISIBLE);
                    } else {
                        mDetailLoadMoreBtn.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onError(String msg) {
                mDetailLoading = false;
                MusicActivity.MAIN.post(() -> {
                    MusicActivity.toast("加载歌曲失败: " + msg);
                    if (mDetailPage == 1) {
                        mDetailLoadMoreBtn.setVisibility(View.GONE);
                    } else {
                        mDetailLoadMoreBtn.setText("加载更多");
                    }
                });
            }
        });
    }

    private void addDetailSongRows(List<MusicSearchApi.Song> songs) {
        MusicSearchApi.Song current = MusicActivity.sPlayer != null
            ? MusicActivity.sPlayer.getCurrent() : null;
        String currentId = current != null ? current.id : null;

        for (final MusicSearchApi.Song song : songs) {
            boolean isPlaying = song.id != null && song.id.equals(currentId);

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(MusicActivity.rd(6, MusicActivity.CLR_CARD));
            row.setPadding(MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.setMargins(0, 0, 0, MusicActivity.dp(4));
            row.setLayoutParams(rowLp);

            ImageView cover = new ImageView(getContext());
            int cs = MusicActivity.dp(40);
            cover.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(MusicActivity.rd(4, 0xFFDDDDDD));
            MusicActivity.loadCover(cover, song.cover);
            row.addView(cover);

            LinearLayout textCol = new LinearLayout(getContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding(MusicActivity.dp(10), 0, MusicActivity.dp(8), 0);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

            TextView title = new TextView(getContext());
            title.setText(song.title);
            title.setTextSize(14);
            title.setTextColor(isPlaying ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_TEXT);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            textCol.addView(title);

            TextView artist = new TextView(getContext());
            artist.setText(song.artist);
            artist.setTextSize(12);
            artist.setTextColor(MusicActivity.CLR_TEXT2);
            artist.setSingleLine(true);
            artist.setPadding(0, MusicActivity.dp(2), 0, 0);
            textCol.addView(artist);

            row.addView(textCol);

            if (song.duration > 0) {
                TextView dur = new TextView(getContext());
                int min = song.duration / 60;
                int sec = song.duration % 60;
                dur.setText(String.format("%d:%02d", min, sec));
                dur.setTextSize(12);
                dur.setTextColor(MusicActivity.CLR_TEXT2);
                dur.setPadding(MusicActivity.dp(4), 0, 0, 0);
                row.addView(dur);
            }

            final int songIdx = mDetailSongs.indexOf(song);
            row.setOnClickListener(v -> {
                MusicActivity.playSongs(new ArrayList<>(mDetailSongs), songIdx);
            });

            mDetailSongList.addView(row);
        }
    }

    // ===== Data Loading =====

    private void loadCategories() {
        KgApi.getPlaylistCategories(new KgApi.CategoryCallback() {
            @Override
            public void onResult(List<KgApi.Playlist> hot) {
                mCategories = hot;
                MusicActivity.MAIN.post(() -> {
                    if (mCategories.isEmpty()) return;
                    mChipsRow.removeAllViews();
                    for (int i = 0; i < mCategories.size(); i++) {
                        mChipsRow.addView(buildChip(mCategories.get(i).title, i));
                    }
                    loadPlaylists();
                });
            }

            @Override
            public void onError(String msg) {
                MusicActivity.MAIN.post(() -> MusicActivity.toast("加载分类失败: " + msg));
            }
        });
    }

    private void loadPlaylists() {
        if (mLoading) return;
        mLoading = true;

        String tagId = "";
        if (mCategories.size() > 0 && mSelectedCatIdx < mCategories.size()) {
            tagId = mCategories.get(mSelectedCatIdx).id;
        }

        if (mPage == 1) {
            mLoadMoreBtn.setVisibility(View.GONE);
        } else {
            mLoadMoreBtn.setText("加载中...");
        }

        KgApi.getPlaylistsByTag(tagId, mPage, new KgApi.PlaylistCallback() {
            @Override
            public void onResult(List<KgApi.Playlist> playlists) {
                mLoading = false;
                if (mPage == 1) {
                    mPlaylists.clear();
                    mGridContainer.removeAllViews();
                }
                mPlaylists.addAll(playlists);
                mHasMore = playlists.size() >= 30;

                MusicActivity.MAIN.post(() -> {
                    addPlaylistCards(playlists);
                    if (mHasMore) {
                        mLoadMoreBtn.setText("加载更多");
                        mLoadMoreBtn.setVisibility(View.VISIBLE);
                    } else {
                        mLoadMoreBtn.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    // ===== Conversion =====

    private List<MusicSearchApi.Song> convertSongs(List<KgApi.Song> kgSongs) {
        List<MusicSearchApi.Song> result = new ArrayList<>();
        for (KgApi.Song ks : kgSongs) {
            MusicSearchApi.Song ms = new MusicSearchApi.Song();
            ms.id = ks.hash;
            ms.hash = ks.hash;
            ms.title = ks.title;
            ms.artist = ks.artist;
            ms.album = ks.album;
            ms.cover = ks.cover;
            ms.duration = ks.duration;
            ms.platform = 0;
            result.add(ms);
        }
        return result;
    }
}
