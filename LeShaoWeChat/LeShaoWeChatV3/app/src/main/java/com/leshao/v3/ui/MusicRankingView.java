package com.leshao.v3.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class MusicRankingView {

    private static final int CLR_SILVER = 0xFF94A3B8;
    private static final int CLR_BRONZE = 0xFFB45309;
    private static final String[] CHIP_LABELS = {"官方", "精选", "曲风", "语言", "其他"};
    private static final int[] CHIP_CLASSIFIES = {1, 2, 3, 4, 5};

    private Activity mActivity;
    private LinearLayout mRoot;
    private ScrollView mContentScroll;
    private LinearLayout mRankingList;
    private ScrollView mDetailScroll;
    private LinearLayout mDetailList;
    private List<TextView> mChipViews = new ArrayList<>();
    private int mSelectedClassify = 1;

    private List<KgApi.Ranking> mAllRankings = new ArrayList<>();
    private ProgressBar mProgressBar;
    private boolean mDidLoadData;

    public View createView(Activity activity) {
        mActivity = activity;
        mDidLoadData = false;
        mAllRankings.clear();
        mChipViews.clear();

        mContentScroll = new ScrollView(mActivity);

        mRoot = new LinearLayout(mActivity);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(MusicActivity.dp(11), MusicActivity.sStatusBarH + MusicActivity.dp(6),
                MusicActivity.dp(11), MusicActivity.dp(8));

        buildTitleBar();
        buildFilterChips();

        mProgressBar = new ProgressBar(mActivity);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-2, -2);
        pbLp.gravity = Gravity.CENTER_HORIZONTAL;
        pbLp.topMargin = MusicActivity.dp(20);
        mProgressBar.setLayoutParams(pbLp);
        mRoot.addView(mProgressBar);

        buildRankingList();
        mRoot.addView(mRankingList);

        buildDetailView();
        mRoot.addView(mDetailScroll);

        mContentScroll.addView(mRoot);
        return mContentScroll;
    }

    public void onViewReady() {
        if (!mDidLoadData) loadData();
    }

    private void buildTitleBar() {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(MusicActivity.dp(8), MusicActivity.dp(4), MusicActivity.dp(8), MusicActivity.dp(4));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(mActivity);
        title.setText("\u6392\u884C\u699C");
        title.setTextSize(18);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        bar.addView(title);

        mRoot.addView(bar);
    }

    private void buildFilterChips() {
        HorizontalScrollView hsv = new HorizontalScrollView(mActivity);
        hsv.setPadding(MusicActivity.dp(6), 0, MusicActivity.dp(6), MusicActivity.dp(6));
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chipContainer = new LinearLayout(mActivity);
        chipContainer.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < CHIP_LABELS.length; i++) {
            final int idx = i;
            TextView chip = new TextView(mActivity);
            chip.setText(CHIP_LABELS[i]);
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(MusicActivity.dp(10), MusicActivity.dp(5), MusicActivity.dp(10), MusicActivity.dp(5));
            chip.setTypeface(null, Typeface.BOLD);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(MusicActivity.dp(3), 0, MusicActivity.dp(3), 0);
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
            chip.setBackground(MusicActivity.rd(14, sel ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_INPUT));
            chip.setTextColor(sel ? 0xFFFFFFFF : MusicActivity.CLR_TEXT2);
        }
    }

    private void buildRankingList() {
        mRankingList = new LinearLayout(mActivity);
        mRankingList.setOrientation(LinearLayout.VERTICAL);
        mRankingList.setPadding(MusicActivity.dp(6), 0, MusicActivity.dp(6), MusicActivity.dp(6));
    }

    private void buildDetailView() {
        mDetailScroll = new ScrollView(mActivity);
        mDetailScroll.setVisibility(View.GONE);
        mDetailList = new LinearLayout(mActivity);
        mDetailList.setOrientation(LinearLayout.VERTICAL);
        mDetailScroll.addView(mDetailList);
    }

    private void loadData() {
        mDidLoadData = true;
        mProgressBar.setVisibility(View.VISIBLE);

        KgApi.getTopLists(new KgApi.RankingCallback() {
            public void onResult(List<KgApi.Ranking> list) {
                if (mActivity == null) return;
                mProgressBar.setVisibility(View.GONE);
                mAllRankings = list != null ? list : new ArrayList<>();
                if (mAllRankings.isEmpty()) {
                    showError("暂无排行榜数据");
                } else {
                    filterRankings();
                }
            }
            public void onError(String msg) {
                if (mActivity == null) return;
                mProgressBar.setVisibility(View.GONE);
                showError("加载排行榜失败: " + msg);
            }
        });
    }

    private void showError(String msg) {
        TextView tv = new TextView(mActivity);
        tv.setText(msg);
        tv.setTextSize(12);
        tv.setTextColor(MusicActivity.CLR_TEXT2);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, MusicActivity.dp(20), 0, 0);
        mRankingList.removeAllViews();
        mRankingList.addView(tv);
    }

    private void filterRankings() {
        mRankingList.removeAllViews();

        List<KgApi.Ranking> filtered = new ArrayList<>();
        for (KgApi.Ranking r : mAllRankings) {
            if (r.classify == mSelectedClassify) filtered.add(r);
        }

        if (filtered.isEmpty()) {
            TextView tv = new TextView(mActivity);
            tv.setText("该分类下暂无排行");
            tv.setTextSize(12);
            tv.setTextColor(MusicActivity.CLR_TEXT2);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, MusicActivity.dp(20), 0, 0);
            mRankingList.addView(tv);
            return;
        }

        for (int i = 0; i < filtered.size(); i++) {
            mRankingList.addView(createRankingItem(i + 1, filtered.get(i)));
        }
        showRankingView();
    }

    public void showRankingView() {
        mRankingList.setVisibility(View.VISIBLE);
        mDetailScroll.setVisibility(View.GONE);
    }

    public boolean isDetailShown() {
        return mDetailScroll != null && mDetailScroll.getVisibility() == View.VISIBLE;
    }

    private void showDetailView() {
        mRankingList.setVisibility(View.GONE);
        mDetailScroll.setVisibility(View.VISIBLE);
    }

    private View createRankingItem(int position, KgApi.Ranking rank) {
        LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
        item.setPadding(MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8));

        int itemH = MusicActivity.dp(56);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, itemH);
        lp.setMargins(0, MusicActivity.dp(3), 0, MusicActivity.dp(3));
        item.setLayoutParams(lp);

        TextView rankTv = new TextView(mActivity);
        rankTv.setText(String.valueOf(position));
        rankTv.setTextSize(16);
        rankTv.setTypeface(null, Typeface.BOLD);
        rankTv.setGravity(Gravity.CENTER);
        rankTv.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(28), -1));
        if (position == 1) rankTv.setTextColor(MusicActivity.CLR_GOLD);
        else if (position == 2) rankTv.setTextColor(CLR_SILVER);
        else if (position == 3) rankTv.setTextColor(CLR_BRONZE);
        else { rankTv.setTextColor(MusicActivity.CLR_TEXT2); rankTv.setTextSize(12); }
        item.addView(rankTv);

        ImageView cover = new ImageView(mActivity);
        int coverSize = MusicActivity.dp(40);
        LinearLayout.LayoutParams covLp = new LinearLayout.LayoutParams(coverSize, coverSize);
        covLp.setMargins(MusicActivity.dp(6), 0, MusicActivity.dp(8), 0);
        cover.setLayoutParams(covLp);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(4));
        coverBg.setColor(MusicActivity.CLR_INPUT);
        cover.setBackground(coverBg);
        MusicActivity.loadCover(cover, rank.cover);
        item.addView(cover);

        LinearLayout textCol = new LinearLayout(mActivity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView titleTv = new TextView(mActivity);
        titleTv.setText(rank.title);
        titleTv.setTextSize(13);
        titleTv.setTextColor(MusicActivity.CLR_TEXT);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setSingleLine(true);
        textCol.addView(titleTv);

        TextView countTv = new TextView(mActivity);
        String desc = rank.updateFrequency != null && !rank.updateFrequency.isEmpty()
                ? rank.updateFrequency : rank.songCount + " 首";
        countTv.setText(desc);
        countTv.setTextSize(11);
        countTv.setTextColor(MusicActivity.CLR_TEXT2);
        textCol.addView(countTv);

        item.addView(textCol);

        TextView arrow = new TextView(mActivity);
        arrow.setText("\u25B6");
        arrow.setTextSize(10);
        arrow.setTextColor(MusicActivity.CLR_TEXT2);
        arrow.setGravity(Gravity.CENTER);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(24), -1));
        item.addView(arrow);

        item.setOnClickListener(v -> openRankingDetail(rank));
        return item;
    }

    private void openRankingDetail(KgApi.Ranking rank) {
        mDetailList.removeAllViews();

        LinearLayout backRow = new LinearLayout(mActivity);
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        backRow.setPadding(MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(6));
        backRow.setBackgroundColor(MusicActivity.CLR_CARD);

        TextView backBtn = new TextView(mActivity);
        backBtn.setText("\u2190 返回");
        backBtn.setTextSize(14);
        backBtn.setTextColor(MusicActivity.CLR_ACCENT);
        backBtn.setOnClickListener(v -> showRankingView());
        backRow.addView(backBtn);

        TextView detailTitle = new TextView(mActivity);
        detailTitle.setText(rank.title);
        detailTitle.setTextSize(13);
        detailTitle.setTextColor(MusicActivity.CLR_TEXT);
        detailTitle.setTypeface(null, Typeface.BOLD);
        detailTitle.setSingleLine(true);
        detailTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        backRow.addView(detailTitle);

        TextView playAllBtn = new TextView(mActivity);
        playAllBtn.setText("播放全部");
        playAllBtn.setTextSize(11);
        playAllBtn.setTextColor(0xFFFFFFFF);
        playAllBtn.setBackground(MusicActivity.rd(12, MusicActivity.CLR_ACCENT));
        playAllBtn.setPadding(MusicActivity.dp(10), MusicActivity.dp(4), MusicActivity.dp(10), MusicActivity.dp(4));
        backRow.addView(playAllBtn);

        mDetailList.addView(backRow);

        View sep = new View(mActivity);
        sep.setBackgroundColor(MusicActivity.CLR_DIV);
        sep.setLayoutParams(new LinearLayout.LayoutParams(-1, MusicActivity.dp(1)));
        mDetailList.addView(sep);

        ProgressBar pb = new ProgressBar(mActivity);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(MusicActivity.dp(20), MusicActivity.dp(20));
        pbLp.gravity = Gravity.CENTER;
        pbLp.topMargin = MusicActivity.dp(28);
        pb.setLayoutParams(pbLp);
        mDetailList.addView(pb);

        showDetailView();

        KgApi.getTopListDetail(rank.id, 1, new KgApi.PlaylistSongsCallback() {
            public void onResult(List<KgApi.Song> songs, int total) {
                if (mActivity == null) return;
                mDetailList.removeView(pb);

                List<MusicSearchApi.Song> msSongs = new ArrayList<>();
                for (KgApi.Song ks : songs) {
                    MusicSearchApi.Song ms = new MusicSearchApi.Song();
                    ms.id = (ks.hash != null && !ks.hash.isEmpty()) ? ks.hash : ks.id;
                    ms.hash = ks.hash;
                    ms.hash320 = ks.hash320;
                    ms.sqHash = ks.sqHash;
                    ms.originHash = ks.originHash;
                    ms.albumId = ks.albumId;
                    ms.albumAudioId = ks.albumAudioId;
                    ms.title = ks.title;
                    ms.artist = ks.artist;
                    ms.cover = ks.cover;
                    ms.duration = ks.duration;
                    ms.platform = 0;
                    msSongs.add(ms);
                }

                playAllBtn.setOnClickListener(v -> {
                    if (!msSongs.isEmpty()) MusicActivity.playSongs(msSongs, 0);
                });

                for (int i = 0; i < songs.size(); i++) {
                    final int idx = i;
                    View itemView = createDetailSongItem(i + 1, songs.get(i), msSongs.get(i));
                    mDetailList.addView(itemView);
                }
            }
            public void onError(String msg) {
                if (mActivity == null) return;
                mDetailList.removeView(pb);
                TextView errTv = new TextView(mActivity);
                errTv.setText("加载失败: " + msg);
                errTv.setTextSize(12);
                errTv.setTextColor(MusicActivity.CLR_RED);
                errTv.setPadding(MusicActivity.dp(16), MusicActivity.dp(20), MusicActivity.dp(16), 0);
                mDetailList.addView(errTv);
            }
        });
    }

    private View createDetailSongItem(int index, KgApi.Song kgSong, MusicSearchApi.Song msSong) {
        LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(MusicActivity.dp(8), MusicActivity.dp(7), MusicActivity.dp(8), MusicActivity.dp(7));
        item.setBackgroundColor(MusicActivity.CLR_CARD);

        TextView idxTv = new TextView(mActivity);
        idxTv.setText(String.valueOf(index));
        idxTv.setTextSize(9);
        idxTv.setTextColor(MusicActivity.CLR_TEXT2);
        idxTv.setGravity(Gravity.CENTER);
        idxTv.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(24), -2));
        item.addView(idxTv);

        ImageView cover = new ImageView(mActivity);
        int cs = MusicActivity.dp(34);
        LinearLayout.LayoutParams covLp = new LinearLayout.LayoutParams(cs, cs);
        covLp.setMargins(MusicActivity.dp(4), 0, MusicActivity.dp(8), 0);
        cover.setLayoutParams(covLp);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(3));
        coverBg.setColor(MusicActivity.CLR_INPUT);
        cover.setBackground(coverBg);
        MusicActivity.loadCover(cover, kgSong.cover);
        item.addView(cover);

        LinearLayout textCol = new LinearLayout(mActivity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView titleTv = new TextView(mActivity);
        titleTv.setText(kgSong.title != null ? kgSong.title : "");
        titleTv.setTextSize(13);
        titleTv.setTextColor(MusicActivity.CLR_TEXT);
        titleTv.setSingleLine(true);
        textCol.addView(titleTv);

        TextView artistTv = new TextView(mActivity);
        artistTv.setText(kgSong.artist != null ? kgSong.artist : "");
        artistTv.setTextSize(11);
        artistTv.setTextColor(MusicActivity.CLR_TEXT2);
        artistTv.setSingleLine(true);
        textCol.addView(artistTv);

        item.addView(textCol);

        TextView playBtn = new TextView(mActivity);
        playBtn.setText("\u25B6");
        playBtn.setTextSize(14);
        playBtn.setTextColor(MusicActivity.CLR_ACCENT);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setPadding(MusicActivity.dp(8), 0, MusicActivity.dp(4), 0);
        playBtn.setOnClickListener(v -> MusicActivity.playSong(msSong));
        item.addView(playBtn);

        item.setOnClickListener(v -> MusicActivity.playSong(msSong));

        return item;
    }
}
