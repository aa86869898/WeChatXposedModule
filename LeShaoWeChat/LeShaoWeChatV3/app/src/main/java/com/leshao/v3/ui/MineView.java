package com.leshao.v3.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MineView {

    private Activity mActivity;
    private LinearLayout mFavList;
    private LinearLayout mHistoryList;
    private TextView mStatHistory, mStatFav, mStatDown;

    public View createView(Activity activity) {
        mActivity = activity;

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(MusicActivity.CLR_BG);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(MusicActivity.dp(14), MusicActivity.sStatusBarH + MusicActivity.dp(10),
                MusicActivity.dp(14), MusicActivity.dp(24));

        buildProfileHeader(root);
        buildStatRow(root);
        buildHistorySection(root);
        buildFavoritesSection(root);
        buildLocalSection(root);

        scroll.addView(root);
        return scroll;
    }

    public void onViewReady() {
        refreshData();
    }

    private void buildProfileHeader(LinearLayout parent) {
        LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, MusicActivity.dp(4), 0, MusicActivity.dp(14));

        int avSize = MusicActivity.dp(44);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setShape(GradientDrawable.OVAL);
        avBg.setColor(MusicActivity.CLR_ACCENT_LIGHT);

        TextView avatar = new TextView(mActivity);
        avatar.setText("🎧");
        avatar.setTextSize(20);
        avatar.setGravity(Gravity.CENTER);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(avSize, avSize));
        avatar.setBackground(avBg);
        header.addView(avatar);

        LinearLayout infoCol = new LinearLayout(mActivity);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(MusicActivity.dp(10), 0, 0, 0);

        TextView name = new TextView(mActivity);
        name.setText("\u4E50\u5C11\u97F3\u4E50");
        name.setTextSize(16);
        name.setTextColor(MusicActivity.CLR_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        infoCol.addView(name);

        TextView sub = new TextView(mActivity);
        sub.setText("\u4EAB\u53D7\u97F3\u4E50\uFF0C\u4EAB\u53D7\u751F\u6D3B");
        sub.setTextSize(11);
        sub.setTextColor(MusicActivity.CLR_TEXT2);
        sub.setPadding(0, MusicActivity.dp(2), 0, 0);
        infoCol.addView(sub);

        header.addView(infoCol);
        parent.addView(header);
    }

    private void buildStatRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(MusicActivity.rd(10, MusicActivity.CLR_CARD));
        row.setPadding(MusicActivity.dp(8), MusicActivity.dp(10), MusicActivity.dp(8), MusicActivity.dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = MusicActivity.dp(12);
        row.setLayoutParams(rlp);

        String[][] statIcons = {{"\uD83C\uDFB6", "\u542C\u6B4C\u8BB0\u5F55"}, {"\uD83D\uDC96", "\u6211\u7684\u6536\u85CF"}, {"\uD83D\uDCBE", "\u5DF2\u4E0B\u8F7D"}};
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(mActivity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

            TextView icon = new TextView(mActivity);
            icon.setText(statIcons[i][0]);
            icon.setTextSize(18);
            icon.setGravity(Gravity.CENTER);
            cell.addView(icon);

            TextView val = new TextView(mActivity);
            val.setText("0");
            val.setTextSize(16);
            val.setTextColor(MusicActivity.CLR_TEXT);
            val.setTypeface(null, Typeface.BOLD);
            val.setGravity(Gravity.CENTER);
            val.setPadding(0, MusicActivity.dp(2), 0, 0);
            cell.addView(val);

            TextView lbl = new TextView(mActivity);
            lbl.setText(statIcons[i][1]);
            lbl.setTextSize(10);
            lbl.setTextColor(MusicActivity.CLR_TEXT2);
            lbl.setGravity(Gravity.CENTER);
            cell.addView(lbl);

            if (i == 0) mStatHistory = val;
            else if (i == 1) mStatFav = val;
            else mStatDown = val;

            row.addView(cell);
            if (i < 2) {
                View div = new View(mActivity);
                div.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(1), MusicActivity.dp(28)));
                div.setBackgroundColor(MusicActivity.CLR_DIV);
                row.addView(div);
            }
        }
        parent.addView(row);
    }

    private void buildHistorySection(LinearLayout parent) {
        parent.addView(buildSectionCard("\uD83C\uDFB6  \u542C\u6B4C\u5386\u53F2", mHistoryList = new LinearLayout(mActivity)));
        mHistoryList.setOrientation(LinearLayout.VERTICAL);
    }

    private void buildFavoritesSection(LinearLayout parent) {
        parent.addView(buildSectionCard("\uD83D\uDC96  \u6211\u7684\u6536\u85CF", mFavList = new LinearLayout(mActivity)));
        mFavList.setOrientation(LinearLayout.VERTICAL);
    }

    private LinearLayout buildSectionCard(String title, LinearLayout contentList) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(MusicActivity.rd(10, MusicActivity.CLR_CARD));
        card.setPadding(MusicActivity.dp(14), MusicActivity.dp(12), MusicActivity.dp(14), MusicActivity.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = MusicActivity.dp(12);
        card.setLayoutParams(lp);

        TextView header = new TextView(mActivity);
        header.setText(title);
        header.setTextSize(14);
        header.setTextColor(MusicActivity.CLR_TEXT);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, MusicActivity.dp(8));
        card.addView(header);

        contentList.setOrientation(LinearLayout.VERTICAL);
        card.addView(contentList);
        return card;
    }

    private void buildLocalSection(LinearLayout parent) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(MusicActivity.rd(10, MusicActivity.CLR_CARD));
        card.setPadding(MusicActivity.dp(14), MusicActivity.dp(12), MusicActivity.dp(14), MusicActivity.dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = MusicActivity.dp(12);
        card.setLayoutParams(lp);

        TextView header = new TextView(mActivity);
        header.setText("\uD83D\uDCBE  \u4E0B\u8F7D\u7BA1\u7406");
        header.setTextSize(14);
        header.setTextColor(MusicActivity.CLR_TEXT);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, MusicActivity.dp(6));
        card.addView(header);

        TextView hint = new TextView(mActivity);
        hint.setText("\u5DF2\u4E0B\u8F7D\u7684\u6B4C\u66F2\u5C06\u663E\u793A\u5728\u8FD9\u91CC");
        hint.setTextSize(11);
        hint.setTextColor(MusicActivity.CLR_TEXT2);
        hint.setPadding(0, 0, 0, MusicActivity.dp(8));
        card.addView(hint);

        TextView scanBtn = new TextView(mActivity);
        scanBtn.setText("\u626B\u63CF\u672C\u5730\u97F3\u4E50");
        scanBtn.setTextSize(12);
        scanBtn.setTextColor(0xFFFFFFFF);
        scanBtn.setGravity(Gravity.CENTER);
        scanBtn.setPadding(MusicActivity.dp(10), MusicActivity.dp(7), MusicActivity.dp(10), MusicActivity.dp(7));
        scanBtn.setBackground(MusicActivity.rd(8, MusicActivity.CLR_ACCENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        scanBtn.setLayoutParams(slp);
        card.addView(scanBtn);

        parent.addView(card);
    }

    private void refreshData() {
        refreshHistory();
        refreshFavorites();
    }

    private void refreshHistory() {
        mHistoryList.removeAllViews();
        List<MusicSearchApi.Song> history = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getHistory() : null;
        int count = (history != null) ? Math.min(history.size(), 20) : 0;
        if (mStatHistory != null) mStatHistory.setText(String.valueOf(history != null ? history.size() : 0));

        if (history == null || history.isEmpty()) {
            mHistoryList.addView(emptyHint("\u6682\u65E0\u542C\u6B4C\u8BB0\u5F55"));
            return;
        }

        for (int i = 0; i < count; i++) {
            MusicSearchApi.Song s = history.get(i);
            if (s == null) continue;
            mHistoryList.addView(buildSongItem(s, i + 1, false, null));
        }
    }

    private void refreshFavorites() {
        mFavList.removeAllViews();
        SharedPreferences prefs = mActivity.getSharedPreferences("music_fav", Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("favorites", new LinkedHashSet<>());
        if (mStatFav != null) mStatFav.setText(String.valueOf(saved.size()));

        if (saved.isEmpty()) {
            mFavList.addView(emptyHint("\u6682\u65E0\u6536\u85CF\u6B4C\u66F2"));
            return;
        }

        int i = 0;
        for (String entry : saved) {
            i++;
            String[] parts = entry.split("\\|");
            String title = parts.length > 0 ? parts[0] : "\u672A\u77E5";
            String artist = parts.length > 1 ? parts[1] : "";
            String hash = parts.length > 2 ? parts[2] : "";
            String cover = parts.length > 3 ? parts[3] : "";

            MusicSearchApi.Song ms = new MusicSearchApi.Song();
            ms.hash = hash;
            ms.id = hash;
            ms.title = title;
            ms.artist = artist;
            ms.cover = cover;

            mFavList.addView(buildSongItem(ms, i, true, entry));
        }
    }

    private View buildSongItem(MusicSearchApi.Song song, int index, boolean isFavorite, String favEntry) {
        LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(MusicActivity.dp(4), MusicActivity.dp(6), MusicActivity.dp(4), MusicActivity.dp(6));
        item.setOnClickListener(v -> MusicActivity.playSong(song));

        TextView idx = new TextView(mActivity);
        idx.setText(String.valueOf(index));
        idx.setTextSize(12);
        int idxClr = index <= 3 ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_TEXT2;
        idx.setTextColor(idxClr);
        idx.setTypeface(null, Typeface.BOLD);
        idx.setGravity(Gravity.CENTER);
        idx.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(24), MusicActivity.dp(24)));
        item.addView(idx);

        ImageView coverIv = new ImageView(mActivity);
        int cs = MusicActivity.dp(32);
        coverIv.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        coverIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable covBg = new GradientDrawable();
        covBg.setCornerRadius(MusicActivity.dp(4));
        covBg.setColor(0xFFE8ECF0);
        coverIv.setBackground(covBg);
        coverIv.setPadding(MusicActivity.dp(6), MusicActivity.dp(6), MusicActivity.dp(6), MusicActivity.dp(6));
        item.addView(coverIv);

        LinearLayout infoCol = new LinearLayout(mActivity);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setPadding(MusicActivity.dp(8), 0, 0, 0);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView title = new TextView(mActivity);
        title.setText(song.title);
        title.setTextSize(13);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setSingleLine(true);
        title.setTypeface(null, Typeface.BOLD);
        infoCol.addView(title);

        TextView artist = new TextView(mActivity);
        artist.setText(song.artist);
        artist.setTextSize(11);
        artist.setTextColor(MusicActivity.CLR_TEXT2);
        artist.setSingleLine(true);
        artist.setPadding(0, MusicActivity.dp(1), 0, 0);
        infoCol.addView(artist);

        item.addView(infoCol);

        if (song.cover != null && !song.cover.isEmpty()) {
            MusicActivity.loadCover(coverIv, song.cover);
        }

        if (isFavorite && favEntry != null) {
            TextView delBtn = new TextView(mActivity);
            delBtn.setText("\u2715");
            delBtn.setTextSize(14);
            delBtn.setTextColor(MusicActivity.CLR_TEXT2);
            delBtn.setGravity(Gravity.CENTER);
            delBtn.setPadding(MusicActivity.dp(6), MusicActivity.dp(4), MusicActivity.dp(6), MusicActivity.dp(4));
            delBtn.setOnClickListener(v -> removeFav(favEntry));
            item.addView(delBtn);
        }

        View sep = new View(mActivity);
        sep.setLayoutParams(new LinearLayout.LayoutParams(-1, MusicActivity.dp(1)));
        sep.setBackgroundColor(MusicActivity.CLR_DIV);
        mFavList.addView(sep);

        return item;
    }

    private void removeFav(String entry) {
        SharedPreferences prefs = mActivity.getSharedPreferences("music_fav", Context.MODE_PRIVATE);
        Set<String> saved = new LinkedHashSet<>(prefs.getStringSet("favorites", new LinkedHashSet<>()));
        saved.remove(entry);
        prefs.edit().putStringSet("favorites", saved).apply();
        refreshFavorites();
    }

    private TextView emptyHint(String text) {
        TextView tv = new TextView(mActivity);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(MusicActivity.CLR_TEXT2);
        tv.setPadding(MusicActivity.dp(4), MusicActivity.dp(4), 0, MusicActivity.dp(4));
        return tv;
    }

}
