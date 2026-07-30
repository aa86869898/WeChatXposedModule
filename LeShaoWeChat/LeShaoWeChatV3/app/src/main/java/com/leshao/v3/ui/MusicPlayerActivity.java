package com.leshao.v3.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class MusicPlayerActivity extends Activity {

    private ImageView mCover, mPlayBtn;
    private TextView mTitle, mArtist, mCurrentTime, mTotalTime;
    private SeekBar mSeekBar;
    private TextView mPrevBtn, mNextBtn, mModeBtn;
    private TextView mLyricText;
    private LinearLayout mRoot;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressRunner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(MusicActivity.CLR_BG);
        mRoot.setPadding(0, 0, 0, MusicActivity.dp(16));

        buildHeader();
        buildCoverArea();
        buildProgressArea();
        buildLyricArea();
        buildControls();

        setContentView(mRoot);
        updateUI();
        startProgressRunner();
    }

    void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(MusicActivity.dp(16), MusicActivity.dp(12), MusicActivity.dp(16), MusicActivity.dp(8));

        TextView back = new TextView(this);
        back.setText("\u2190 返回");
        back.setTextSize(15);
        back.setTextColor(MusicActivity.CLR_TEXT);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("正在播放");
        title.setTextSize(17);
        title.setTextColor(MusicActivity.CLR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        title.setGravity(Gravity.CENTER);
        header.addView(title);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(MusicActivity.dp(48), 1));
        header.addView(spacer);

        mRoot.addView(header);
    }

    void buildCoverArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setGravity(Gravity.CENTER);
        area.setPadding(0, MusicActivity.dp(12), 0, MusicActivity.dp(10));

        int coverSize = MusicActivity.dp(140);
        mCover = new ImageView(this);
        mCover.setLayoutParams(new LinearLayout.LayoutParams(coverSize, coverSize));
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setCornerRadius(MusicActivity.dp(12));
        coverBg.setColor(0xFFDDDDDD);
        mCover.setBackground(coverBg);
        area.addView(mCover);

        mTitle = new TextView(this);
        mTitle.setTextSize(16);
        mTitle.setTextColor(MusicActivity.CLR_TEXT);
        mTitle.setTypeface(null, Typeface.BOLD);
        mTitle.setSingleLine(true);
        mTitle.setPadding(0, MusicActivity.dp(12), 0, MusicActivity.dp(4));
        area.addView(mTitle);

        mArtist = new TextView(this);
        mArtist.setTextSize(12);
        mArtist.setTextColor(MusicActivity.CLR_TEXT2);
        mArtist.setSingleLine(true);
        area.addView(mArtist);

        mRoot.addView(area);
    }

    void buildProgressArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(MusicActivity.dp(32), 0, MusicActivity.dp(32), MusicActivity.dp(12));

        mSeekBar = new SeekBar(this);
        mSeekBar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && MusicActivity.sPlayer != null) {
                    MusicActivity.sPlayer.seekTo(progress * 1000);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        area.addView(mSeekBar);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);

        mCurrentTime = new TextView(this);
        mCurrentTime.setTextSize(12);
        mCurrentTime.setTextColor(MusicActivity.CLR_TEXT2);
        mCurrentTime.setText("0:00");
        timeRow.addView(mCurrentTime);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        timeRow.addView(spacer);

        mTotalTime = new TextView(this);
        mTotalTime.setTextSize(12);
        mTotalTime.setTextColor(MusicActivity.CLR_TEXT2);
        mTotalTime.setText("0:00");
        timeRow.addView(mTotalTime);

        area.addView(timeRow);
        mRoot.addView(area);
    }

    void buildLyricArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1.0f));
        area.setGravity(Gravity.CENTER);
        area.setPadding(MusicActivity.dp(32), MusicActivity.dp(8), MusicActivity.dp(32), MusicActivity.dp(8));

        mLyricText = new TextView(this);
        mLyricText.setTextSize(15);
        mLyricText.setTextColor(MusicActivity.CLR_TEXT);
        mLyricText.setGravity(Gravity.CENTER);
        mLyricText.setLineSpacing(MusicActivity.dp(8), 1.0f);
        mLyricText.setText("暂无歌词");
        area.addView(mLyricText);

        mRoot.addView(area);
    }

    void buildControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(MusicActivity.dp(16), MusicActivity.dp(12), MusicActivity.dp(16), MusicActivity.dp(8));

        mModeBtn = btn("🔀", 16);
        controls.addView(mModeBtn);

        View sp1 = new View(this);
        sp1.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        controls.addView(sp1);

        mPrevBtn = btn("⏮", 18);
        mPrevBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.prev(); updateUI(); }
        });
        controls.addView(mPrevBtn);

        int playSize = MusicActivity.dp(44);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(MusicActivity.CLR_ACCENT);
        playBg.setStroke(MusicActivity.dp(2), MusicActivity.CLR_ACCENT);

        mPlayBtn = new ImageView(this);
        mPlayBtn.setLayoutParams(new LinearLayout.LayoutParams(playSize, playSize));
        mPlayBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mPlayBtn.setBackground(playBg);
        mPlayBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.togglePause(); updatePlayBtn(); }
        });
        controls.addView(mPlayBtn);

        mNextBtn = btn("⏭", 18);
        mNextBtn.setOnClickListener(v -> {
            if (MusicActivity.sPlayer != null) { MusicActivity.sPlayer.next(); updateUI(); }
        });
        controls.addView(mNextBtn);

        View sp2 = new View(this);
        sp2.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
        controls.addView(sp2);

        TextView listBtn = btn("📋", 16);
        controls.addView(listBtn);

        mRoot.addView(controls);
    }

    TextView btn(String emoji, int sizeDp) {
        TextView tv = new TextView(this);
        int pad = MusicActivity.dp(8);
        tv.setPadding(pad, pad, pad, pad);
        tv.setText(emoji);
        tv.setTextSize(sizeDp);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    void updateUI() {
        MusicSearchApi.Song song = MusicActivity.sPlayer != null ? MusicActivity.sPlayer.getCurrent() : null;
        if (song == null) return;
        mTitle.setText(song.title);
        mArtist.setText(song.artist);
        MusicActivity.loadCover(mCover, song.cover);
        int dur = MusicActivity.sPlayer.getDuration();
        mTotalTime.setText(formatTime(dur / 1000));
        mSeekBar.setMax(dur > 0 ? dur / 1000 : 100);
        updatePlayBtn();
    }

    void updatePlayBtn() {
        boolean playing = MusicActivity.sPlayer != null && MusicActivity.sPlayer.isPlaying();
        mPlayBtn.setImageDrawable(MusicActivity.emoji(playing ? "\u23F8" : "\u25B6", MusicActivity.dp(18)));
    }

    void startProgressRunner() {
        mProgressRunner = new Runnable() {
            @Override public void run() {
                if (MusicActivity.sPlayer != null && MusicActivity.sPlayer.isPlaying()) {
                    int pos = MusicActivity.sPlayer.getPosition() / 1000;
                    int dur = MusicActivity.sPlayer.getDuration() / 1000;
                    mSeekBar.setProgress(pos);
                    mCurrentTime.setText(formatTime(pos));
                    mTotalTime.setText(formatTime(dur));
                    mSeekBar.setMax(dur > 0 ? dur : 100);
                }
                mHandler.postDelayed(this, 500);
            }
        };
        mHandler.post(mProgressRunner);
    }

    String formatTime(int sec) {
        int m = sec / 60;
        int s = sec % 60;
        return String.format("%d:%02d", m, s);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mProgressRunner != null) mHandler.removeCallbacks(mProgressRunner);
    }
}
