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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

public class MusicRankingFragment extends Fragment {

    private static final int CLR_SILVER = 0xFF94A3B8;
    private static final int CLR_BRONZE = 0xFFB45309;

    private static final String[] CHIP_LABELS = {"官方", "精选", "曲风", "语言", "其他"};
    private static final int[] CHIP_CLASSIFIES = {1, 2, 3, 4, 5};

    private LinearLayout mRoot;
    private FrameLayout mContentContainer;
    private ScrollView mRankingScroll;
    private LinearLayout mRankingList;
    private ScrollView mDetailScroll;
    private LinearLayout mDetailList;
    private List<TextView> mChipViews = new ArrayList<>();
    private int mSelectedClassify = 1;

    private List<KgApi.Ranking> mAllRankings = new ArrayList<>();
    private List<KgApi.Ranking> mFilteredRankings = new ArrayList<>();
    private boolean mDidLoadRankings = false;
    private ProgressBar mProgressBar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mRoot = new LinearLayout(getContext());
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(MusicActivity.CLR_BG);

        buildTitleBar();
        buildFilterChips();

        mContentContainer = new FrameLayout(getContext());
        mContentContainer.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));
        mRoot.addView(mContentContainer);

        mProgressBar = new ProgressBar(getContext());
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                MusicActivity.dp(36), MusicActivity.dp(36));
        pbLp.gravity = Gravity.CENTER;
        mProgressBar.setLayoutParams(pbLp);
        mProgressBar.setVisibility(View.VISIBLE);
        mContentContainer.addView(mProgressBar);

        buildRankingList();
        mRankingScroll = new ScrollView(getContext());
        mRankingScroll.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        mRankingScroll.setVisibility(View.GONE);
        mRankingScroll.addView(mRankingList);
        mContentContainer.addView(mRankingScroll);

        buildDetailView();
        mDetailScroll = new ScrollView(getContext());
        mDetailScroll.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        mDetailScroll.setVisibility(View.GONE);
        mDetailScroll.addView(mDetailList);
        mContentContainer.addView(mDetailScroll);

        loadData();

        return mRoot;
    }

    private void buildTitleBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(MusicActivity.dp(16), MusicActivity.dp(16),
                MusicActivity.dp(16), MusicActivity.dp(8));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(getContext());
        title.setText("\u6392\u884C\u699C");
        title.setTextSize(18);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        bar.addView(title);

        mRoot.addView(bar);
    }

    private void buildFilterChips() {
        HorizontalScrollView hsv = new HorizontalScrollView(getContext());
        hsv.setPadding(MusicActivity.dp(12), 0, MusicActivity.dp(12), MusicActivity.dp(8));
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chipContainer = new LinearLayout(getContext());
        chipContainer.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < CHIP_LABELS.length; i++) {
            final int idx = i;
            TextView chip = new TextView(getContext());
            chip.setText(CHIP_LABELS[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(MusicActivity.dp(16), MusicActivity.dp(6),
                    MusicActivity.dp(16), MusicActivity.dp(6));
            chip.setTypeface(null, Typeface.BOLD);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(MusicActivity.dp(4), 0, MusicActivity.dp(4), 0);
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> {
                mSelectedClassify = CHIP_CLASSIFIES[idx];
                updateChipSelection();
                filterRankings();
            });

            chipContainer.addView(chip);
            mChipViews.add(chip);
        }

        hsv.addView(chipContainer);
        mRoot.addView(hsv);
        updateChipSelection();
    }

    private void updateChipSelection() {
        for (int i = 0; i < mChipViews.size(); i++) {
            TextView chip = mChipViews.get(i);
            boolean sel = (CHIP_CLASSIFIES[i] == mSelectedClassify);
            if (sel) {
                chip.setBackground(MusicActivity.rd(20, MusicActivity.CLR_ACCENT));
                chip.setTextColor(0xFFFFFFFF);
            } else {
                chip.setBackground(MusicActivity.rd(20, MusicActivity.CLR_INPUT));
                chip.setTextColor(MusicActivity.CLR_TEXT2);
            }
        }
    }

    private void buildRankingList() {
        mRankingList = new LinearLayout(getContext());
        mRankingList.setOrientation(LinearLayout.VERTICAL);
        mRankingList.setPadding(MusicActivity.dp(8), 0, MusicActivity.dp(8), MusicActivity.dp(8));
    }

    private void buildDetailView() {
        mDetailList = new LinearLayout(getContext());
        mDetailList.setOrientation(LinearLayout.VERTICAL);
    }

    private void loadData() {
        KgApi.getTopLists(new KgApi.RankingCallback() {
            @Override
            public void onResult(List<KgApi.Ranking> list) {
                if (getActivity() == null) return;
                mAllRankings = list;
                mDidLoadRankings = true;
                mProgressBar.setVisibility(View.GONE);
                filterRankings();
            }

            @Override
            public void onError(String msg) {
                if (getActivity() == null) return;
                mDidLoadRankings = true;
                mProgressBar.setVisibility(View.GONE);
                MusicActivity.toast("加载排行榜失败: " + msg);
            }
        });
    }

    private void filterRankings() {
        mFilteredRankings.clear();
        for (KgApi.Ranking r : mAllRankings) {
            if (r.classify == mSelectedClassify) {
                mFilteredRankings.add(r);
            }
        }
        refreshRankingViews();
        showRankingView();
    }

    private void showRankingView() {
        mDetailScroll.setVisibility(View.GONE);
        mRankingScroll.setVisibility(View.VISIBLE);
    }

    private void showDetailView() {
        mRankingScroll.setVisibility(View.GONE);
        mDetailScroll.setVisibility(View.VISIBLE);
    }

    private void refreshRankingViews() {
        mRankingList.removeAllViews();

        for (int i = 0; i < mFilteredRankings.size(); i++) {
            KgApi.Ranking rank = mFilteredRankings.get(i);
            mRankingList.addView(createRankingItem(i + 1, rank));
        }
    }

    private View createRankingItem(int position, KgApi.Ranking rank) {
        LinearLayout item = new LinearLayout(getContext());
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(MusicActivity.rd(12, MusicActivity.CLR_CARD));
        item.setPadding(MusicActivity.dp(12), MusicActivity.dp(12),
                MusicActivity.dp(12), MusicActivity.dp(12));

        int itemH = MusicActivity.dp(80);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, itemH);
        lp.setMargins(0, MusicActivity.dp(4), 0, MusicActivity.dp(4));
        item.setLayoutParams(lp);

        TextView rankTv = new TextView(getContext());
        rankTv.setText(String.valueOf(position));
        rankTv.setTextSize(18);
        rankTv.setTypeface(null, Typeface.BOLD);
        rankTv.setGravity(Gravity.CENTER);
        rankTv.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(36), -1));

        if (position == 1) {
            rankTv.setTextColor(MusicActivity.CLR_GOLD);
        } else if (position == 2) {
            rankTv.setTextColor(CLR_SILVER);
        } else if (position == 3) {
            rankTv.setTextColor(CLR_BRONZE);
        } else {
            rankTv.setTextColor(MusicActivity.CLR_TEXT2);
            rankTv.setTextSize(14);
        }
        item.addView(rankTv);

        ImageView cover = new ImageView(getContext());
        int coverSize = MusicActivity.dp(56);
        LinearLayout.LayoutParams covLp = new LinearLayout.LayoutParams(coverSize, coverSize);
        covLp.setMargins(MusicActivity.dp(8), 0, MusicActivity.dp(10), 0);
        cover.setLayoutParams(covLp);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(8));
        coverBg.setColor(MusicActivity.CLR_INPUT);
        cover.setBackground(coverBg);
        MusicActivity.loadCover(cover, rank.cover);
        item.addView(cover);

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        textCol.setPadding(MusicActivity.dp(2), 0, 0, 0);

        TextView titleTv = new TextView(getContext());
        titleTv.setText(rank.title);
        titleTv.setTextSize(15);
        titleTv.setTextColor(MusicActivity.CLR_TEXT);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setSingleLine(true);
        textCol.addView(titleTv);

        TextView countTv = new TextView(getContext());
        countTv.setText(rank.songCount + " 首歌曲");
        countTv.setTextSize(12);
        countTv.setTextColor(MusicActivity.CLR_TEXT2);
        textCol.addView(countTv);

        item.addView(textCol);

        TextView arrow = new TextView(getContext());
        arrow.setText("\u25B6");
        arrow.setTextSize(14);
        arrow.setTextColor(MusicActivity.CLR_TEXT2);
        arrow.setGravity(Gravity.CENTER);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(28), -1));
        item.addView(arrow);

        item.setOnClickListener(v -> openRankingDetail(rank));
        return item;
    }

    private void openRankingDetail(KgApi.Ranking rank) {
        mDetailList.removeAllViews();

        LinearLayout backRow = new LinearLayout(getContext());
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        backRow.setPadding(MusicActivity.dp(12), MusicActivity.dp(12),
                MusicActivity.dp(12), MusicActivity.dp(8));
        backRow.setBackgroundColor(MusicActivity.CLR_CARD);

        TextView backBtn = new TextView(getContext());
        backBtn.setText("\u2190");
        backBtn.setTextSize(20);
        backBtn.setTextColor(MusicActivity.CLR_TEXT);
        backBtn.setPadding(0, 0, MusicActivity.dp(12), 0);
        backBtn.setOnClickListener(v -> showRankingView());
        backRow.addView(backBtn);

        TextView detailTitle = new TextView(getContext());
        detailTitle.setText(rank.title);
        detailTitle.setTextSize(17);
        detailTitle.setTextColor(MusicActivity.CLR_TEXT);
        detailTitle.setTypeface(null, Typeface.BOLD);
        detailTitle.setSingleLine(true);
        backRow.addView(detailTitle);

        View sep = new View(getContext());
        sep.setBackgroundColor(MusicActivity.CLR_DIV);
        sep.setLayoutParams(new LinearLayout.LayoutParams(-1, MusicActivity.dp(1)));
        mDetailList.addView(backRow);
        mDetailList.addView(sep);

        ProgressBar pb = new ProgressBar(getContext());
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                MusicActivity.dp(28), MusicActivity.dp(28));
        pbLp.gravity = Gravity.CENTER;
        pbLp.topMargin = MusicActivity.dp(40);
        pb.setLayoutParams(pbLp);
        mDetailList.addView(pb);

        showDetailView();

        KgApi.getTopListDetail(rank.id, 1, new KgApi.PlaylistSongsCallback() {
            @Override
            public void onResult(List<KgApi.Song> songs, int total) {
                if (getActivity() == null) return;
                mDetailList.removeView(pb);
                for (int i = 0; i < songs.size(); i++) {
                    mDetailList.addView(createDetailSongItem(i + 1, songs.get(i)));
                }
            }

            @Override
            public void onError(String msg) {
                if (getActivity() == null) return;
                mDetailList.removeView(pb);
                MusicActivity.toast("加载失败: " + msg);
            }
        });
    }

    private View createDetailSongItem(int index, KgApi.Song kgSong) {
        LinearLayout item = new LinearLayout(getContext());
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(MusicActivity.dp(12), MusicActivity.dp(10),
                MusicActivity.dp(12), MusicActivity.dp(10));
        item.setBackgroundColor(MusicActivity.CLR_CARD);

        View sep = new View(getContext());
        sep.setBackgroundColor(MusicActivity.CLR_DIV);
        sep.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        item.setTag(sep);

        TextView idxTv = new TextView(getContext());
        idxTv.setText(String.valueOf(index));
        idxTv.setTextSize(13);
        idxTv.setTextColor(MusicActivity.CLR_TEXT2);
        idxTv.setGravity(Gravity.CENTER);
        idxTv.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(32), -2));
        item.addView(idxTv);

        ImageView cover = new ImageView(getContext());
        int cs = MusicActivity.dp(40);
        LinearLayout.LayoutParams covLp = new LinearLayout.LayoutParams(cs, cs);
        covLp.setMargins(MusicActivity.dp(6), 0, MusicActivity.dp(10), 0);
        cover.setLayoutParams(covLp);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(4));
        coverBg.setColor(MusicActivity.CLR_INPUT);
        cover.setBackground(coverBg);
        MusicActivity.loadCover(cover, kgSong.cover);
        item.addView(cover);

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView titleTv = new TextView(getContext());
        titleTv.setText(kgSong.title);
        titleTv.setTextSize(14);
        titleTv.setTextColor(MusicActivity.CLR_TEXT);
        titleTv.setSingleLine(true);
        textCol.addView(titleTv);

        TextView artistTv = new TextView(getContext());
        artistTv.setText(kgSong.artist);
        artistTv.setTextSize(12);
        artistTv.setTextColor(MusicActivity.CLR_TEXT2);
        artistTv.setSingleLine(true);
        textCol.addView(artistTv);

        item.addView(textCol);

        TextView playBtn = new TextView(getContext());
        playBtn.setText("\u25B6");
        playBtn.setTextSize(16);
        playBtn.setTextColor(MusicActivity.CLR_ACCENT);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(40), -1));
        item.addView(playBtn);

        item.setOnClickListener(v -> {
            MusicSearchApi.Song msSong = convertSong(kgSong);
            MusicActivity.playSong(msSong);
        });

        return item;
    }

    private MusicSearchApi.Song convertSong(KgApi.Song kgSong) {
        MusicSearchApi.Song s = new MusicSearchApi.Song();
        s.id = kgSong.hash != null ? kgSong.hash : "";
        s.hash = kgSong.hash != null ? kgSong.hash : "";
        s.title = kgSong.title != null ? kgSong.title : "";
        s.artist = kgSong.artist != null ? kgSong.artist : "";
        s.album = kgSong.album != null ? kgSong.album : "";
        s.cover = kgSong.cover != null ? kgSong.cover : "";
        s.duration = kgSong.duration;
        s.platform = 0;
        return s;
    }
}
