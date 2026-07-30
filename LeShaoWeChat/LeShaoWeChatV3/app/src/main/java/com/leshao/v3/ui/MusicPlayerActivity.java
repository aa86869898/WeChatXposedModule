package com.leshao.v3.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

public class MusicPlayerActivity extends Activity {

    private ImageView mCover;
    private TextView mTitle, mArtist, mCurrentTime, mTotalTime;
    private SeekBar mSeekBar;
    private TextView mPrevBtn, mPlayBtn, mNextBtn, mModeBtn;
    private TextView mLyricText;
    private LinearLayout mRoot;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressRunner;
    private boolean mUserSeeking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            mRoot = new LinearLayout(this);
            mRoot.setOrientation(LinearLayout.VERTICAL);
            mRoot.setBackgroundColor(MusicActivity.CLR_BG);
            mRoot.setPadding(0, MusicActivity.sStatusBarH, 0, 0);

            buildHeader();
            buildCoverArea();
            buildProgressArea();
            buildLyricArea();
            buildControls();

            setContentView(mRoot);
            updateUI();
            startProgressRunner();
            Log.d("MusicPlayer", "onCreate OK");
        } catch (Throwable e) {
            Log.e("MusicPlayer", "onCreate CRASH: " + Log.getStackTraceString(e));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopProgressRunner();
    }

    void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(MusicActivity.dp(12), MusicActivity.dp(8), MusicActivity.dp(12), MusicActivity.dp(4));

        TextView back = new TextView(this);
        back.setText("\u2190");
        back.setTextSize(18);
        back.setTextColor(MusicActivity.CLR_TEXT);
        back.setPadding(0, 0, MusicActivity.dp(12), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView topTitle = new TextView(this);
        topTitle.setText("正在播放");
        topTitle.setTextSize(15);
        topTitle.setTextColor(MusicActivity.CLR_TEXT);
        topTitle.setTypeface(null, Typeface.BOLD);
        titleCol.addView(topTitle);

        header.addView(titleCol);

        mRoot.addView(header);
    }

    void buildCoverArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setPadding(0, MusicActivity.dp(8), 0, MusicActivity.dp(8));
        area.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));

        int coverSize = (int) (Math.min(
            getResources().getDisplayMetrics().widthPixels * 0.6f,
            MusicActivity.dp(220)));
        FrameLayout coverFrame = new FrameLayout(this);
        coverFrame.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));

        GradientDrawable shadow = new GradientDrawable();
        shadow.setCornerRadius(MusicActivity.dp(16));
        shadow.setColor(0x33000000);
        shadow.setSize(MusicActivity.dp(4), MusicActivity.dp(4));

        mCover = new ImageView(this);
        FrameLayout.LayoutParams covLp = new FrameLayout.LayoutParams(coverSize, coverSize);
        covLp.setMargins(MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4), MusicActivity.dp(4));
        mCover.setLayoutParams(covLp);
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(14));
        coverBg.setColor(0xFFDDDDDD);
        mCover.setBackground(coverBg);
        coverFrame.addView(mCover);

        area.addView(coverFrame);

        mTitle = new TextView(this);
        mTitle.setTextSize(17);
        mTitle.setTextColor(MusicActivity.CLR_TEXT);
        mTitle.setTypeface(null, Typeface.BOLD);
        mTitle.setSingleLine(true);
        mTitle.setPadding(MusicActivity.dp(32), MusicActivity.dp(14), MusicActivity.dp(32), MusicActivity.dp(4));
        mTitle.setGravity(Gravity.CENTER);
        area.addView(mTitle);

        mArtist = new TextView(this);
        mArtist.setTextSize(13);
        mArtist.setTextColor(MusicActivity.CLR_TEXT2);
        mArtist.setSingleLine(true);
        mArtist.setPadding(MusicActivity.dp(32), 0, MusicActivity.dp(32), 0);
        mArtist.setGravity(Gravity.CENTER);
        area.addView(mArtist);

        mRoot.addView(area);
    }

    void buildProgressArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(MusicActivity.dp(24), 0, MusicActivity.dp(24), MusicActivity.dp(8));

        mSeekBar = new SeekBar(this);
        mSeekBar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        mSeekBar.setMax(1000);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && MusicActivity.sPlayer != null) {
                    int dur = MusicActivity.sPlayer.getDuration();
                    if (dur > 0) MusicActivity.sPlayer.seekTo(p * dur / 1000);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { mUserSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { mUserSeeking = false; }
        });
        area.addView(mSeekBar);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);

        mCurrentTime = new TextView(this);
        mCurrentTime.setTextSize(11);
        mCurrentTime.setTextColor(MusicActivity.CLR_TEXT2);
        mCurrentTime.setText("0:00");
        timeRow.addView(mCurrentTime);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        timeRow.addView(spacer);

        mTotalTime = new TextView(this);
        mTotalTime.setTextSize(11);
        mTotalTime.setTextColor(MusicActivity.CLR_TEXT2);
        mTotalTime.setText("0:00");
        timeRow.addView(mTotalTime);

        area.addView(timeRow);
        mRoot.addView(area);
    }

    void buildLyricArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setPadding(MusicActivity.dp(24), MusicActivity.dp(4), MusicActivity.dp(24), MusicActivity.dp(4));

        mLyricText = new TextView(this);
        mLyricText.setTextSize(14);
        mLyricText.setTextColor(MusicActivity.CLR_TEXT2);
        mLyricText.setGravity(Gravity.CENTER);
        mLyricText.setLineSpacing(MusicActivity.dp(6), 1.0f);
        mLyricText.setText("暂无歌词");
        area.addView(mLyricText);

        mRoot.addView(area);
    }

    void buildControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(8), MusicActivity.dp(16));

        mModeBtn = ctrlBtn("\uD83D\uDD01", 12);
        mModeBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) {
                int mode = MusicActivity.sPlayer.getPlayMode();
                String[] modes = {"\uD83D\uDD01", "\uD83D\uDD02", "\uD83D\uDD00"};
                MusicActivity.sPlayer.setPlayMode((mode + 1) % 3);
                mModeBtn.setText(modes[(mode + 1) % 3]);
            }
        });
        controls.addView(mModeBtn);

        View sp1 = new View(this);
        sp1.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        controls.addView(sp1);

        mPrevBtn = ctrlBtn("\u23EE", 20);
        mPrevBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.prev(); updateUI(); }
        });
        controls.addView(mPrevBtn);

        View sp2 = new View(this);
        sp2.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(8), 1));
        controls.addView(sp2);

        int playSize = MusicActivity.dp(52);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(MusicActivity.CLR_ACCENT);

        mPlayBtn = new TextView(this);
        mPlayBtn.setText("\u25B6");
        mPlayBtn.setTextSize(22);
        mPlayBtn.setTextColor(0xFFFFFFFF);
        mPlayBtn.setGravity(Gravity.CENTER);
        mPlayBtn.setBackground(playBg);
        mPlayBtn.setLayoutParams(new LinearLayout.LayoutParams(playSize, playSize));
        mPlayBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer == null || MusicActivity.sPlayer.getCurrent() == null) return;
            if (MusicActivity.sPlayer.isPlaying()) MusicActivity.sPlayer.pause();
            else MusicActivity.sPlayer.resume();
            updateUI();
            if (MusicActivity.sInstance != null) MusicActivity.sInstance.refreshPlayerBar();
        });
        controls.addView(mPlayBtn);

        View sp3 = new View(this);
        sp3.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(8), 1));
        controls.addView(sp3);

        mNextBtn = ctrlBtn("\u23ED", 20);
        mNextBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.next(); updateUI(); }
        });
        controls.addView(mNextBtn);

        View sp4 = new View(this);
        sp4.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        controls.addView(sp4);

        TextView playlistBtn = ctrlBtn("\uD83D\uDCCB", 12);
        playlistBtn.setOnClickListener(v -> showPlaylist());
        controls.addView(playlistBtn);

        mRoot.addView(controls);
    }

    private TextView ctrlBtn(String emoji, int size) {
        TextView btn = new TextView(this);
        btn.setText(emoji);
        btn.setTextSize(size);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(MusicActivity.dp(10), MusicActivity.dp(10), MusicActivity.dp(10), MusicActivity.dp(10));
        return btn;
    }

    private void updateUI() {
        MusicSearchApi.Song song = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
        if (song == null) {
            mTitle.setText("未在播放");
            mArtist.setText("");
            mCover.setImageBitmap(null);
            return;
        }
        mTitle.setText(song.title);
        mArtist.setText(song.artist);
        MusicActivity.loadCover(mCover, song.cover);

        boolean playing = MusicActivity.sPlayer != null && MusicActivity.sPlayer.isPlaying();
        mPlayBtn.setText(playing ? "\u23F8" : "\u25B6");

        mSeekBar.setMax(1000);
        mCurrentTime.setText("0:00");
        mTotalTime.setText(formatTime(song.duration * 1000));
    }

    private void startProgressRunner() {
        mProgressRunner = new Runnable() {
            @Override
            public void run() {
                if (MusicActivity.sPlayer != null && MusicActivity.sPlayer.isPlaying() && !mUserSeeking) {
                    int pos = MusicActivity.sPlayer.getPosition();
                    int dur = MusicActivity.sPlayer.getDuration();
                    if (dur > 0) {
                        mSeekBar.setProgress(pos * 1000 / dur);
                        mCurrentTime.setText(formatTime(pos));
                        mTotalTime.setText(formatTime(dur));
                    }
                }
                if (MusicActivity.sInstance != null) MusicActivity.sInstance.refreshPlayerBar();
                mHandler.postDelayed(this, 1000);
            }
        };
        mHandler.post(mProgressRunner);
    }

    private void stopProgressRunner() {
        if (mProgressRunner != null) mHandler.removeCallbacks(mProgressRunner);
    }

    private String formatTime(int ms) {
        int s = ms / 1000;
        int m = s / 60;
        s = s % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    private void showPlaylist() {
        List<MusicSearchApi.Song> list = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getPlaylist() : null;
        if (list == null || list.isEmpty()) {
            MusicActivity.toast("播放列表为空");
            return;
        }
        MusicSearchApi.Song current = MusicActivity.sPlayer.getCurrent();

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFFFFFFFF);
        container.setPadding(MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(12), MusicActivity.dp(12));

        for (int i = 0; i < Math.min(list.size(), 50); i++) {
            MusicSearchApi.Song s = list.get(i);
            boolean isCurrent = current != null && s.id.equals(current.id);

            TextView tv = new TextView(this);
            tv.setText((i + 1) + ". " + s.title + " - " + s.artist);
            tv.setTextSize(13);
            tv.setTextColor(isCurrent ? MusicActivity.CLR_ACCENT : MusicActivity.CLR_TEXT);
            tv.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            tv.setSingleLine(true);
            tv.setPadding(0, MusicActivity.dp(5), 0, MusicActivity.dp(5));
            final int idx = i;
            tv.setOnClickListener(v -> {
                if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.playFromPlaylist(idx); updateUI(); }
            });
            container.addView(tv);
        }

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
            .setTitle("播放列表 (" + list.size() + "首)")
            .setView(container)
            .setPositiveButton("关闭", null)
            .create();
        dialog.show();
    }
}
