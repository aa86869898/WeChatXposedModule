package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

public class MusicPlayerTabView {

    private Activity mActivity;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private ImageView mCover;
    private TextView mTitle, mArtist, mStatus;
    private TextView mPlaylistBtn, mHistoryBtn, mFullPlayerBtn;
    private LinearLayout mPlaylistContainer, mHistoryContainer;

    public View createView(Activity activity) {
        mActivity = activity;

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(MusicActivity.CLR_BG);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(MusicActivity.dp(12), MusicActivity.sStatusBarH + MusicActivity.dp(10),
                MusicActivity.dp(12), MusicActivity.dp(8));

        buildHeader(root);
        buildNowPlaying(root);
        buildQuickActions(root);
        buildPlaylistSection(root);
        buildHistorySection(root);

        scroll.addView(root);
        return scroll;
    }

    public void onViewReady() {
        refreshUI();
    }

    private void buildHeader(LinearLayout parent) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, MusicActivity.dp(4), 0, MusicActivity.dp(8));

        TextView title = new TextView(mActivity);
        title.setText("播放器");
        title.setTextSize(17);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(title);

        parent.addView(row);
    }

    private void buildNowPlaying(LinearLayout parent) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(MusicActivity.rd(12, MusicActivity.CLR_CARD));
        card.setPadding(MusicActivity.dp(16), MusicActivity.dp(20), MusicActivity.dp(16), MusicActivity.dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = MusicActivity.dp(10);
        card.setLayoutParams(lp);

        int coverSize = MusicActivity.dp(160);
        mCover = new ImageView(mActivity);
        mCover.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(80));
        coverBg.setColor(0xFFDDDDDD);
        mCover.setBackground(coverBg);
        mCover.setOnClickListener(v -> openFullPlayer());
        card.addView(mCover);

        mTitle = new TextView(mActivity);
        mTitle.setTextSize(16);
        mTitle.setTextColor(MusicActivity.CLR_TEXT);
        mTitle.setTypeface(null, Typeface.BOLD);
        mTitle.setSingleLine(true);
        mTitle.setPadding(0, MusicActivity.dp(14), 0, MusicActivity.dp(4));
        card.addView(mTitle);

        mArtist = new TextView(mActivity);
        mArtist.setTextSize(12);
        mArtist.setTextColor(MusicActivity.CLR_TEXT2);
        mArtist.setSingleLine(true);
        card.addView(mArtist);

        mStatus = new TextView(mActivity);
        mStatus.setTextSize(11);
        mStatus.setTextColor(MusicActivity.CLR_ACCENT);
        mStatus.setPadding(0, MusicActivity.dp(8), 0, 0);
        card.addView(mStatus);

        parent.addView(card);
    }

    private void buildQuickActions(LinearLayout parent) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = MusicActivity.dp(10);
        row.setLayoutParams(lp);

        mFullPlayerBtn = actionBtn("\uD83C\uDFB5  全屏播放", 0xFF6366F1);
        mFullPlayerBtn.setOnClickListener(v -> openFullPlayer());
        row.addView(mFullPlayerBtn);

        View spacer = new View(mActivity);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(8), 1));
        row.addView(spacer);

        mPlaylistBtn = actionBtn("\uD83D\uDCCB  播放列表", 0xFF10B981);
        mPlaylistBtn.setOnClickListener(v -> togglePlaylist());
        row.addView(mPlaylistBtn);

        parent.addView(row);
    }

    private TextView actionBtn(String text, int color) {
        TextView btn = new TextView(mActivity);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(MusicActivity.rd(8, color));
        btn.setPadding(MusicActivity.dp(8), MusicActivity.dp(10), MusicActivity.dp(8), MusicActivity.dp(10));
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        return btn;
    }

    private void buildPlaylistSection(LinearLayout parent) {
        mPlaylistContainer = new LinearLayout(mActivity);
        mPlaylistContainer.setOrientation(LinearLayout.VERTICAL);
        mPlaylistContainer.setVisibility(View.GONE);
        mPlaylistContainer.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
        mPlaylistContainer.setPadding(MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = MusicActivity.dp(8);
        mPlaylistContainer.setLayoutParams(lp);

        TextView header = new TextView(mActivity);
        header.setText("当前播放列表");
        header.setTextSize(13);
        header.setTextColor(MusicActivity.CLR_TEXT);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, MusicActivity.dp(6));
        mPlaylistContainer.addView(header);

        parent.addView(mPlaylistContainer);
    }

    private void buildHistorySection(LinearLayout parent) {
        mHistoryContainer = new LinearLayout(mActivity);
        mHistoryContainer.setOrientation(LinearLayout.VERTICAL);
        mHistoryContainer.setVisibility(View.GONE);
        mHistoryContainer.setBackground(MusicActivity.rd(8, MusicActivity.CLR_CARD));
        mHistoryContainer.setPadding(MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8));

        TextView header = new TextView(mActivity);
        header.setText("播放历史");
        header.setTextSize(13);
        header.setTextColor(MusicActivity.CLR_TEXT);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, MusicActivity.dp(6));
        mHistoryContainer.addView(header);

        parent.addView(mHistoryContainer);
    }

    private void openFullPlayer() {
        MusicSearchApi.Song song = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
        if (song != null) mActivity.startActivity(new Intent(mActivity, MusicPlayerActivity.class));
        else MusicActivity.toast("请先播放歌曲");
    }

    private void togglePlaylist() {
        if (mPlaylistContainer.getChildCount() <= 1) refreshPlaylist();
        mPlaylistContainer.setVisibility(mPlaylistContainer.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
    }

    private void refreshPlaylist() {
        if (mPlaylistContainer.getChildCount() > 1) {
            for (int i = mPlaylistContainer.getChildCount() - 1; i >= 1; i--)
                mPlaylistContainer.removeViewAt(i);
        }
        List<MusicSearchApi.Song> list = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getPlaylist() : null;
        if (list == null || list.isEmpty()) {
            TextView empty = new TextView(mActivity);
            empty.setText("播放列表为空");
            empty.setTextSize(12);
            empty.setTextColor(MusicActivity.CLR_TEXT2);
            empty.setPadding(0, MusicActivity.dp(6), 0, 0);
            mPlaylistContainer.addView(empty);
            return;
        }
        MusicSearchApi.Song current = MusicActivity.sPlayer.getCurrent();
        for (int i = 0; i < list.size(); i++) {
            MusicSearchApi.Song s = list.get(i);
            boolean isCurrent = current != null && s.id.equals(current.id);

            TextView tv = new TextView(mActivity);
            tv.setText((i + 1) + ". " + s.title + " - " + s.artist);
            tv.setTextSize(12);
            tv.setTextColor(isCurrent ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_TEXT);
            tv.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            tv.setSingleLine(true);
            tv.setPadding(0, MusicActivity.dp(3), 0, MusicActivity.dp(3));
            final int idx = i;
            tv.setOnClickListener(v -> {
                if (MusicActivity.sPlayer != null) MusicActivity.sPlayer.playFromPlaylist(idx);
                if (MusicActivity.sInstance != null) MusicActivity.sInstance.refreshPlayerBar();
                refreshUI();
            });
            mPlaylistContainer.addView(tv);
        }
    }

    private void refreshHistory() {
        if (mHistoryContainer.getChildCount() > 1) {
            for (int i = mHistoryContainer.getChildCount() - 1; i >= 1; i--)
                mHistoryContainer.removeViewAt(i);
        }
        List<MusicSearchApi.Song> list = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getHistory() : null;
        if (list == null || list.isEmpty()) {
            TextView empty = new TextView(mActivity);
            empty.setText("暂无播放历史");
            empty.setTextSize(12);
            empty.setTextColor(MusicActivity.CLR_TEXT2);
            empty.setPadding(0, MusicActivity.dp(6), 0, 0);
            mHistoryContainer.addView(empty);
            return;
        }
        for (int i = 0; i < Math.min(list.size(), 20); i++) {
            MusicSearchApi.Song s = list.get(i);
            TextView tv = new TextView(mActivity);
            tv.setText((i + 1) + ". " + s.title + " - " + s.artist);
            tv.setTextSize(12);
            tv.setTextColor(MusicActivity.CLR_TEXT);
            tv.setSingleLine(true);
            tv.setPadding(0, MusicActivity.dp(3), 0, MusicActivity.dp(3));
            final int idx = i;
            tv.setOnClickListener(v -> MusicActivity.playSong(s));
            mHistoryContainer.addView(tv);
        }
    }

    private void refreshUI() {
        MusicSearchApi.Song current = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
        if (current == null) {
            mTitle.setText("暂未播放");
            mArtist.setText("选择一首歌曲开始播放");
            mStatus.setText("");
        } else {
            mTitle.setText(current.title);
            mArtist.setText(current.artist);
            boolean playing = MusicActivity.sPlayer != null && MusicActivity.sPlayer.isPlaying();
            mStatus.setText(playing ? "正在播放" : "已暂停");
            MusicActivity.loadCover(mCover, current.cover);
        }
    }
}
