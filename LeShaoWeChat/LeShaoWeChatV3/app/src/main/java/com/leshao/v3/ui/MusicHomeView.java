package com.leshao.v3.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
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
    private HorizontalScrollView mArtistScroll;
    private LinearLayout mArtistCards;

    private static final int[] CARD_COLORS = {
        0xFF3B8EFF, 0xFFF59E0B, 0xFFEF4444, 0xFF10B981, 0xFF8B5CF6
    };

    private static final String[] RANK_NUMBERS = {"1", "2", "3", "4", "5"};

    public View createView(Activity activity) {
        mActivity = activity;

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(MusicActivity.CLR_BG);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = MusicActivity.dp(11);
        root.setPadding(p, p, p, p);

        buildHeader(root);
        buildBanner(root);
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
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = MusicActivity.dp(8);
        int padH = MusicActivity.dp(3);
        row.setPadding(padH, padV, padH, padV);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(mActivity);
        title.setText("乐少音乐");
        title.setTextSize(14);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(title);

        ImageView searchIcon = new ImageView(mActivity);
        int iconSize = MusicActivity.dp(17);
        searchIcon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        searchIcon.setImageDrawable(MusicActivity.emoji("\uD83D\uDD0D", MusicActivity.dp(15)));
        searchIcon.setOnClickListener(v -> {
            if (MusicActivity.sInstance != null) MusicActivity.sInstance.showTab(3);
        });
        row.addView(searchIcon);

        parent.addView(row);
    }

    private void buildBanner(LinearLayout parent) {
        FrameLayout banner = new FrameLayout(mActivity);
        int bannerH = MusicActivity.dp(70);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, bannerH);
        bannerLp.topMargin = MusicActivity.dp(4);
        bannerLp.bottomMargin = MusicActivity.dp(6);
        banner.setLayoutParams(bannerLp);
        banner.setBackground(MusicActivity.gradientRounded(
            new int[]{MusicActivity.CLR_ACCENT, 0xFF5B9EFF}, 8));
        banner.setOnClickListener(v -> {
            if (MusicActivity.sInstance != null) MusicActivity.sInstance.showTab(3);
        });

        LinearLayout inner = new LinearLayout(mActivity);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(-1, -1);
        inner.setPadding(0, 0, 0, 0);
        inner.setLayoutParams(innerLp);

        TextView emojiView = new TextView(mActivity);
        emojiView.setText("\uD83C\uDFB5");
        emojiView.setTextSize(18);
        emojiView.setGravity(Gravity.CENTER);
        inner.addView(emojiView);

        TextView title = new TextView(mActivity);
        title.setText("海量音乐 随心畅听");
        title.setTextSize(11);
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.topMargin = MusicActivity.dp(4);
        title.setLayoutParams(titleLp);
        inner.addView(title);

        banner.addView(inner);
        parent.addView(banner);
    }

    private void buildRankingSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(14);
        section.setLayoutParams(sectionLp);

        LinearLayout titleRow = buildSectionTitle("热门排行");
        section.addView(titleRow);

        mRankLoading = new ProgressBar(mActivity);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(-2, -2);
        loadLp.gravity = Gravity.CENTER_HORIZONTAL;
        loadLp.topMargin = MusicActivity.dp(14);
        loadLp.bottomMargin = MusicActivity.dp(14);
        mRankLoading.setLayoutParams(loadLp);
        section.addView(mRankLoading);

        mRankScroll = new HorizontalScrollView(mActivity);
        mRankScroll.setHorizontalScrollBarEnabled(false);
        mRankScroll.setVisibility(View.GONE);
        mRankScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        mRankCards = new LinearLayout(mActivity);
        mRankCards.setOrientation(LinearLayout.HORIZONTAL);
        mRankCards.setPadding(0, MusicActivity.dp(6), 0, 0);
        mRankScroll.addView(mRankCards);
        section.addView(mRankScroll);

        parent.addView(section);
    }

    private void buildPlaylistSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(14);
        section.setLayoutParams(sectionLp);

        LinearLayout titleRow = buildSectionTitle("推荐歌单");
        section.addView(titleRow);

        mPlaylistLoading = new ProgressBar(mActivity);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(-2, -2);
        loadLp.gravity = Gravity.CENTER_HORIZONTAL;
        loadLp.topMargin = MusicActivity.dp(14);
        loadLp.bottomMargin = MusicActivity.dp(14);
        mPlaylistLoading.setLayoutParams(loadLp);
        section.addView(mPlaylistLoading);

        mPlaylistGrid = new LinearLayout(mActivity);
        mPlaylistGrid.setOrientation(LinearLayout.VERTICAL);
        mPlaylistGrid.setVisibility(View.GONE);
        mPlaylistGrid.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        section.addView(mPlaylistGrid);

        parent.addView(section);
    }

    private void buildArtistSection(LinearLayout parent) {
        LinearLayout section = new LinearLayout(mActivity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = MusicActivity.dp(14);
        section.setLayoutParams(sectionLp);

        LinearLayout titleRow = buildSectionTitle("热门歌手");
        section.addView(titleRow);

        mArtistLoading = new ProgressBar(mActivity);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(-2, -2);
        loadLp.gravity = Gravity.CENTER_HORIZONTAL;
        loadLp.topMargin = MusicActivity.dp(14);
        loadLp.bottomMargin = MusicActivity.dp(14);
        mArtistLoading.setLayoutParams(loadLp);
        section.addView(mArtistLoading);

        mArtistScroll = new HorizontalScrollView(mActivity);
        mArtistScroll.setHorizontalScrollBarEnabled(false);
        mArtistScroll.setVisibility(View.GONE);
        mArtistScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        mArtistCards = new LinearLayout(mActivity);
        mArtistCards.setOrientation(LinearLayout.HORIZONTAL);
        mArtistCards.setPadding(0, MusicActivity.dp(6), 0, 0);
        mArtistScroll.addView(mArtistCards);
        section.addView(mArtistScroll);

        parent.addView(section);
    }

    private LinearLayout buildSectionTitle(String titleText) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, MusicActivity.dp(6), 0, MusicActivity.dp(3));
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(mActivity);
        title.setText(titleText);
        title.setTextSize(15);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(title);

        TextView more = new TextView(mActivity);
        more.setText("更多 \u25B6");
        more.setTextSize(12);
        more.setTextColor(MusicActivity.CLR_TEXT2);
        more.setPadding(MusicActivity.dp(6), MusicActivity.dp(3), 0, MusicActivity.dp(3));
        more.setOnClickListener(v -> MusicActivity.toast("功能开发中"));
        row.addView(more);

        return row;
    }

    private void loadData() {
        KgApi.getTopLists(new KgApi.RankingCallback() {
            @Override
            public void onResult(List<KgApi.Ranking> list) {
                mHandler.post(() -> populateRankings(list));
            }
            @Override
            public void onError(String msg) {
                mHandler.post(() -> {
                    mRankLoading.setVisibility(View.GONE);
                });
            }
        });

        KgApi.getRecommendedPlaylists(new KgApi.PlaylistCallback() {
            @Override
            public void onResult(List<KgApi.Playlist> playlists) {
                mHandler.post(() -> populatePlaylists(playlists));
            }
        });

        KgApi.getHotArtists(new KgApi.PlaylistCallback() {
            @Override
            public void onResult(List<KgApi.Playlist> artists) {
                mHandler.post(() -> populateArtists(artists));
            }
        });
    }

    private void populateRankings(List<KgApi.Ranking> list) {
        mRankLoading.setVisibility(View.GONE);
        if (list == null || list.isEmpty()) return;
        mRankScroll.setVisibility(View.VISIBLE);

        int count = Math.min(list.size(), 5);
        int cardW = MusicActivity.dp(110);
        int cardH = MusicActivity.dp(130);
        int gap = MusicActivity.dp(7);

        for (int i = 0; i < count; i++) {
            KgApi.Ranking item = list.get(i);

            FrameLayout card = new FrameLayout(mActivity);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(cardW, cardH);
            if (i > 0) cardLp.leftMargin = gap;
            card.setLayoutParams(cardLp);
            card.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
            card.setElevation(MusicActivity.dp(1));
            card.setOnClickListener(v -> MusicActivity.toast("功能开发中"));

            LinearLayout inner = new LinearLayout(mActivity);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(-1, -1);
            int innerPad = MusicActivity.dp(8);
            inner.setPadding(innerPad, innerPad, innerPad, innerPad);
            inner.setLayoutParams(innerLp);

            FrameLayout colorBox = new FrameLayout(mActivity);
            int boxSize = MusicActivity.dp(60);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(boxSize, boxSize);
            boxLp.gravity = Gravity.CENTER_HORIZONTAL;
            boxLp.topMargin = MusicActivity.dp(7);
            colorBox.setLayoutParams(boxLp);
            GradientDrawable boxBg = new GradientDrawable();
            boxBg.setCornerRadius(MusicActivity.dp(6));
            boxBg.setColor(CARD_COLORS[i % CARD_COLORS.length]);
            colorBox.setBackground(boxBg);

            TextView rankNum = new TextView(mActivity);
            rankNum.setText(RANK_NUMBERS[i]);
            rankNum.setTextSize(20);
            rankNum.setTextColor(0xFFFFFFFF);
            rankNum.setGravity(Gravity.CENTER);
            rankNum.setTypeface(null, android.graphics.Typeface.BOLD);
            colorBox.addView(rankNum, new FrameLayout.LayoutParams(-1, -1));

            inner.addView(colorBox);

            TextView name = new TextView(mActivity);
            name.setText(item.title);
            name.setTextSize(12);
            name.setTextColor(MusicActivity.CLR_TEXT);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
            nameLp.topMargin = MusicActivity.dp(6);
            name.setLayoutParams(nameLp);
            inner.addView(name);

            card.addView(inner);
            mRankCards.addView(card);
        }
    }

    private void populatePlaylists(List<KgApi.Playlist> list) {
        mPlaylistLoading.setVisibility(View.GONE);
        if (list == null || list.isEmpty()) return;
        mPlaylistGrid.setVisibility(View.VISIBLE);

        int count = Math.min(list.size(), 6);
        int gap = MusicActivity.dp(7);
        int cardH = MusicActivity.dp(65);

        mPlaylistGrid.removeAllViews();

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

                if (index < count) {
                    KgApi.Playlist item = list.get(index);
                    FrameLayout card = buildPlaylistCard(item, cardH);
                    row.addView(card, cardLp);
                } else {
                    View spacer = new View(mActivity);
                    row.addView(spacer, cardLp);
                }
            }
            mPlaylistGrid.addView(row);
        }
    }

    private FrameLayout buildPlaylistCard(KgApi.Playlist item, int cardH) {
        FrameLayout card = new FrameLayout(mActivity);
        card.setBackground(MusicActivity.rd(7, MusicActivity.CLR_CARD));
        card.setElevation(MusicActivity.dp(1));
        card.setOnClickListener(v -> MusicActivity.toast("功能开发中"));

        LinearLayout inner = new LinearLayout(mActivity);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams innerLp = new FrameLayout.LayoutParams(-1, -1);
        int pad = MusicActivity.dp(5);
        inner.setPadding(pad, pad, pad, pad);
        inner.setLayoutParams(innerLp);

        int coverSize = MusicActivity.dp(55);
        ImageView cover = new ImageView(mActivity);
        cover.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(4));
        coverBg.setColor(0xFFE0E0E0);
        cover.setBackground(coverBg);
        MusicActivity.loadCover(cover, item.cover);
        inner.addView(cover);

        TextView title = new TextView(mActivity);
        title.setText(item.title);
        title.setTextSize(12);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titleLp.leftMargin = MusicActivity.dp(7);
        title.setLayoutParams(titleLp);
        inner.addView(title);

        card.addView(inner);
        return card;
    }

    private void populateArtists(List<KgApi.Playlist> list) {
        mArtistLoading.setVisibility(View.GONE);
        if (list == null || list.isEmpty()) return;
        mArtistScroll.setVisibility(View.VISIBLE);

        int count = Math.min(list.size(), 8);
        int avatarSize = MusicActivity.dp(60);
        int gap = MusicActivity.dp(10);

        for (int i = 0; i < count; i++) {
            KgApi.Playlist item = list.get(i);

            LinearLayout artistCard = new LinearLayout(mActivity);
            artistCard.setOrientation(LinearLayout.VERTICAL);
            artistCard.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(avatarSize, -2);
            if (i > 0) cardLp.leftMargin = gap;
            artistCard.setLayoutParams(cardLp);
            artistCard.setOnClickListener(v -> MusicActivity.toast("功能开发中"));

            FrameLayout avatarFrame = new FrameLayout(mActivity);
            int frameSize = avatarSize;
            avatarFrame.setLayoutParams(new LinearLayout.LayoutParams(frameSize, frameSize));

            ImageView avatar = new ImageView(mActivity);
            FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(frameSize, frameSize);
            avatar.setLayoutParams(avatarLp);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);

            GradientDrawable avatarBg = new GradientDrawable();
            avatarBg.setShape(GradientDrawable.OVAL);
            avatarBg.setColor(CARD_COLORS[i % CARD_COLORS.length]);
            avatar.setBackground(avatarBg);

            MusicActivity.loadCover(avatar, item.cover);
            avatarFrame.addView(avatar);

            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setStroke(MusicActivity.dp(1), MusicActivity.CLR_ACCENT_LIGHT);
            ring.setColor(Color.TRANSPARENT);
            FrameLayout ringView = new FrameLayout(mActivity);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(frameSize, frameSize);
            ringView.setLayoutParams(ringLp);
            ringView.setBackground(ring);
            avatarFrame.addView(ringView);

            artistCard.addView(avatarFrame);

            TextView name = new TextView(mActivity);
            name.setText(item.title);
            name.setTextSize(12);
            name.setTextColor(MusicActivity.CLR_TEXT);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(avatarSize, -2);
            nameLp.topMargin = MusicActivity.dp(4);
            name.setLayoutParams(nameLp);
            artistCard.addView(name);

            mArtistCards.addView(artistCard);
        }
    }
}
