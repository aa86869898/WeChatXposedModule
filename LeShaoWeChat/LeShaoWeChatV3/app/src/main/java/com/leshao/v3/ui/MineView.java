package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MineView {

    private Activity mActivity;
    private LinearLayout mFavList;
    private LinearLayout mHistoryList;

    private static final int[][] SECTION_CONFIGS = {
        {0, 0, 0}, // placeholder
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
        buildHistorySection(root);
        buildFavoritesSection(root);
        buildLocalSection(root);

        scroll.addView(root);
        return scroll;
    }

    public void onViewReady() {
        refreshData();
    }

    private void buildHeader(LinearLayout parent) {
        TextView title = new TextView(mActivity);
        title.setText("我的");
        title.setTextSize(18);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, MusicActivity.dp(4), 0, MusicActivity.dp(12));
        parent.addView(title);
    }

    private void buildHistorySection(LinearLayout parent) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(MusicActivity.rd(10, MusicActivity.CLR_CARD));
        card.setPadding(MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = MusicActivity.dp(10);
        card.setLayoutParams(lp);

        TextView header = buildCardHeader("听歌历史");
        card.addView(header);

        mHistoryList = new LinearLayout(mActivity);
        mHistoryList.setOrientation(LinearLayout.VERTICAL);
        card.addView(mHistoryList);

        parent.addView(card);
    }

    private void buildFavoritesSection(LinearLayout parent) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(MusicActivity.rd(10, MusicActivity.CLR_CARD));
        card.setPadding(MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = MusicActivity.dp(10);
        card.setLayoutParams(lp);

        TextView header = buildCardHeader("我的收藏");
        card.addView(header);

        mFavList = new LinearLayout(mActivity);
        mFavList.setOrientation(LinearLayout.VERTICAL);
        card.addView(mFavList);

        parent.addView(card);
    }

    private void buildLocalSection(LinearLayout parent) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(MusicActivity.rd(10, MusicActivity.CLR_CARD));
        card.setPadding(MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = MusicActivity.dp(10);
        card.setLayoutParams(lp);

        TextView header = buildCardHeader("下载管理");
        card.addView(header);

        TextView localHint = new TextView(mActivity);
        localHint.setText("已下载的歌曲将显示在这里");
        localHint.setTextSize(11);
        localHint.setTextColor(MusicActivity.CLR_TEXT2);
        localHint.setPadding(0, MusicActivity.dp(4), 0, MusicActivity.dp(4));
        card.addView(localHint);

        // check download dir
        TextView scanBtn = new TextView(mActivity);
        scanBtn.setText("扫描本地音乐");
        scanBtn.setTextSize(12);
        scanBtn.setTextColor(0xFFFFFFFF);
        scanBtn.setBackground(MusicActivity.rd(8, MusicActivity.CLR_ACCENT));
        scanBtn.setGravity(Gravity.CENTER);
        scanBtn.setPadding(MusicActivity.dp(8), MusicActivity.dp(6), MusicActivity.dp(8), MusicActivity.dp(6));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = MusicActivity.dp(4);
        scanBtn.setLayoutParams(slp);
        card.addView(scanBtn);

        parent.addView(card);
    }

    private TextView buildCardHeader(String text) {
        TextView tv = new TextView(mActivity);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(MusicActivity.CLR_TEXT);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 0, 0, MusicActivity.dp(8));
        return tv;
    }

    private void refreshData() {
        refreshHistory();
        refreshFavorites();
    }

    private void refreshHistory() {
        mHistoryList.removeAllViews();
        List<MusicSearchApi.Song> history = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getHistory() : null;
        if (history == null || history.isEmpty()) {
            TextView empty = new TextView(mActivity);
            empty.setText("暂无听歌记录");
            empty.setTextSize(11);
            empty.setTextColor(MusicActivity.CLR_TEXT2);
            mHistoryList.addView(empty);
            return;
        }

        int count = Math.min(history.size(), 20);
        for (int i = 0; i < count; i++) {
            MusicSearchApi.Song s = history.get(i);
            if (s == null) continue;
            TextView tv = new TextView(mActivity);
            tv.setText((i + 1) + ". " + s.title + " - " + s.artist);
            tv.setTextSize(12);
            tv.setTextColor(MusicActivity.CLR_TEXT);
            tv.setSingleLine(true);
            tv.setPadding(0, MusicActivity.dp(3), 0, MusicActivity.dp(3));
            final MusicSearchApi.Song song = s;
            tv.setOnClickListener(v -> MusicActivity.playSong(song));
            mHistoryList.addView(tv);
        }
    }

    private void refreshFavorites() {
        mFavList.removeAllViews();
        SharedPreferences prefs = mActivity.getSharedPreferences("music_fav", Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("favorites", new LinkedHashSet<>());
        if (saved.isEmpty()) {
            TextView empty = new TextView(mActivity);
            empty.setText("暂无收藏歌曲");
            empty.setTextSize(11);
            empty.setTextColor(MusicActivity.CLR_TEXT2);
            mFavList.addView(empty);
            return;
        }
        int i = 0;
        for (String entry : saved) {
            i++;
            String[] parts = entry.split("\\|");
            String title = parts.length > 0 ? parts[0] : "未知";
            String artist = parts.length > 1 ? parts[1] : "";
            String hash = parts.length > 2 ? parts[2] : "";

            TextView tv = new TextView(mActivity);
            tv.setText(i + ". " + title + " - " + artist);
            tv.setTextSize(12);
            tv.setTextColor(MusicActivity.CLR_TEXT);
            tv.setSingleLine(true);
            tv.setPadding(0, MusicActivity.dp(3), 0, MusicActivity.dp(3));
            final String fHash = hash;
            final String fTitle = title;
            final String fArtist = artist;
            tv.setOnClickListener(v -> {
                MusicSearchApi.Song ms = new MusicSearchApi.Song();
                ms.hash = fHash;
                ms.id = fHash;
                ms.title = fTitle;
                ms.artist = fArtist;
                MusicActivity.playSong(ms);
            });
            mFavList.addView(tv);
        }
    }
}
