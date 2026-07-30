package com.leshao.v3.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

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

    private static final int[] CARD_COLORS = {
        0xFF3B8EFF, 0xFFF59E0B, 0xFFEF4444, 0xFF10B981, 0xFF8B5CF6
    };

    public View createView(Activity activity) {
        mActivity = activity;

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(MusicActivity.CLR_BG);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(MusicActivity.dp(12), MusicActivity.sStatusBarH + MusicActivity.dp(8),
                MusicActivity.dp(12), MusicActivity.dp(8));

        buildHeader(root);
        buildSearchBar(root);
        buildRankingSection(root);
        buildPlaylistSection(root);
        buildArtistSection(root);

        scroll.addView(root);
        return scroll;
    }

    public void onViewReady() {
        loadData();
    }

    private void buildHeader(LinearLayout parent) {
        TextView title = new TextView(mActivity);
        title.setText("乐少音乐");
        title.setTextSize(20);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, MusicActivity.dp(4), 0, MusicActivity.dp(4));
        parent.addView(title);
    }

    private void buildSearchBar(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(MusicActivity.rd(20, MusicActivity.CLR_INPUT));
        int padH = MusicActivity.dp(10);
        int padV = MusicActivity.dp(7);
        bar.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.topMargin = MusicActivity.dp(8);
        barLp.bottomMargin = MusicActivity.dp(6);
        bar.setLayoutParams(barLp);

        TextView icon = new TextView(mActivity);
        icon.setText("\uD83D\uDD0D");
        icon.setTextSize(13);
        icon.setPadding(0, 0, MusicActivity.dp(6), 0);
        bar.addView(icon);

        EditText searchInput = new EditText(mActivity);
        searchInput.setHint("搜索歌曲/歌手/专辑");
        searchInput.setTextSize(13);
        searchInput.setTextColor(MusicActivity.CLR_TEXT);
        searchInput.setHintTextColor(MusicActivity.CLR_TEXT2);
        searchInput.setBackground(null);
        searchInput.setSingleLine(true);
        searchInput.setFocusable(false);
        searchInput.setClickable(true);
        searchInput.setCursorVisible(false);
        searchInput.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        searchInput.setOnClickListener(v -> {
            if (MusicActivity.sInstance != null) {
                MusicActivity.sInstance.showTab(3);
                bar.postDelayed(() -> searchInput.clearFocus(), 200);
            }
        });
        bar.addView(searchInput);

        parent.addView(bar);
    }

    private void buildRankingSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(8);
        section.setLayoutParams(sectionLp);
        section.addView(buildSectionTitle("热门排行"));

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

    private void buildPlaylistSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(8);
        section.setLayoutParams(sectionLp);
        section.addView(buildSectionTitle("推荐歌单"));

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
        section.addView(buildSectionTitle("热门歌手"));

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
        KgApi.getTopLists(new KgApi.RankingCallback() {
            public void onResult(List<KgApi.Ranking> list) {
                mHandler.post(() -> populateRankings(list));
            }
            public void onError(String msg) {
                mHandler.post(() -> mRankLoading.setVisibility(View.GONE));
            }
        });

        KgApi.getRecommendedPlaylists(new KgApi.PlaylistCallback() {
            public void onResult(List<KgApi.Playlist> playlists) {
                mHandler.post(() -> populatePlaylists(playlists));
            }
        });

        KgApi.getHotArtists(new KgApi.PlaylistCallback() {
            public void onResult(List<KgApi.Playlist> artists) {
                mHandler.post(() -> populateArtists(artists));
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
            name.setText(item.title.length() > 4 ? item.title.substring(0, 4) : item.title);
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
        title.setText(item.title);
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
                MusicActivity.toast("正在播放: " + item.title);
            }
            public void onError(String msg) {
                MusicActivity.toast("加载歌单失败: " + msg);
            }
        });
    }

    private void populateArtists(List<KgApi.Playlist> list) {
        mArtistLoading.setVisibility(View.GONE);
        if (list == null || list.isEmpty()) return;
        mArtistGrid.setVisibility(View.VISIBLE);
        mArtistGrid.removeAllViews();

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
        avatarBg.setColor(CARD_COLORS[Math.abs(item.title.hashCode()) % CARD_COLORS.length]);
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
                    MusicActivity.toast("未找到 " + item.title + " 的歌曲");
                    return;
                }
                List<MusicSearchApi.Song> msSongs = convertToMs(songs);
                MusicActivity.playSongs(msSongs, 0);
                MusicActivity.toast("正在播放: " + item.title);
            }
        });
    }

    private List<MusicSearchApi.Song> convertToMs(List<KgApi.Song> kgSongs) {
        List<MusicSearchApi.Song> result = new ArrayList<>();
        for (KgApi.Song ks : kgSongs) {
            MusicSearchApi.Song ms = new MusicSearchApi.Song();
            ms.id = ks.hash.isEmpty() ? ks.id : ks.hash;
            ms.hash = ks.hash;
            ms.title = ks.title;
            ms.artist = ks.artist;
            ms.cover = ks.cover;
            ms.duration = ks.duration;
            result.add(ms);
        }
        return result;
    }
}
